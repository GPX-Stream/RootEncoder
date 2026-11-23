package com.pedro.whip

import com.pedro.common.ConnectChecker
import com.pedro.common.base.BaseSender
import com.pedro.common.socket.base.UdpPacket
import com.pedro.common.socket.base.UdpStreamSocket
import com.pedro.whip.dtls.CryptoUtils
import com.pedro.whip.dtls.DtlsConnection
import com.pedro.whip.dtls.DtlsServerConnection
import com.pedro.whip.dtls.DtlsTransport
import com.pedro.whip.dtls.SrtpKeys
import com.pedro.whip.utils.Constants
import com.pedro.whip.webrtc.stun.HeaderType
import com.pedro.whip.webrtc.stun.StunCommand
import com.pedro.whip.webrtc.stun.StunHeader
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * GPX R62 — whether a stop that lands between `onConnectionSuccess` and `whipSender.start()` leaves a
 * sender running. This drives the real [WhipClient] against a fake ingest
 * built from the fork's own classes: a local HTTP server for the WHIP offer, a UDP peer that answers
 * the STUN checks, and the fork's [DtlsServerConnection] as the other end of the DTLS handshake.
 *
 * The stop is placed with the [ConnectChecker]: `disconnect(true)` calls `onDisconnect` after it has
 * read `isStreaming` for the sender-stop guard and before it cancels the connect job. Holding the
 * connect job inside `onConnectionSuccess` and `disconnect()` inside `onDisconnect` puts the stop
 * exactly in the window, with no timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhipConnectStopRaceTest {

  /** Sends what the DTLS server writes back to the client, from the peer's UDP socket. */
  private class PeerSocket(private val udp: DatagramSocket) : UdpStreamSocket() {
    @Volatile var client: InetSocketAddress? = null

    override suspend fun write(bytes: ByteArray) {
      val to = client ?: return
      udp.send(DatagramPacket(bytes, bytes.size, to))
    }

    override suspend fun write(bytes: ByteArray, host: String, port: Int) = write(bytes)
    override suspend fun bind() {}
    override suspend fun read(): ByteArray = ByteArray(0)
    override suspend fun readPacket(): UdpPacket = throw UnsupportedOperationException()
    override suspend fun setRemoteAddress(host: String, port: Int) {}
    override suspend fun getLocalHost(): String = ""
    override suspend fun getLocalPort(): Int = 0
    override suspend fun connect() {}
    override suspend fun close() {}
    override fun isConnected(): Boolean = true
    override fun isReachable(): Boolean = true
  }

  /** A WHIP ingest on this machine: answers the offer, the STUN checks and the DTLS handshake. */
  private class FakeIngest(localIp: String) {
    private val udp = DatagramSocket(InetSocketAddress(InetAddress.getByName(localIp), 0))
    private val peerSocket = PeerSocket(udp)
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val cert = CryptoUtils.generateCert("fake-ingest", BcTlsCrypto(SecureRandom()))
    private val peerFingerprint = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded)
      .joinToString(":") { "%02X".format(it) }
    @Volatile private var closed = false
    @Volatile private var dtlsServer: DtlsServerConnection? = null
    private val localIp = localIp

    val url: String get() = "http://127.0.0.1:${http.address.port}/whip"

    fun start() {
      http.createContext("/") { ex -> handle(ex) }
      http.start()
    }

    private fun handle(ex: HttpExchange) {
      ex.use {
        if (ex.requestMethod == "POST") {
          val offer = ex.requestBody.readBytes().decodeToString()
          val clientFingerprint = offer.lines().map { it.trim() }
            .first { it.startsWith("a=fingerprint:sha-256") }.removePrefix("a=fingerprint:sha-256").trim()
          startPeer(clientFingerprint)
          val answer = "v=0\r\no=- 1 1 IN IP4 $localIp\r\ns=-\r\nt=0 0\r\n" +
            "a=ice-lite\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
            "a=ice-ufrag:srvfrag\r\n" +
            "a=ice-pwd:srvpassword0123456789ab\r\n" +
            "a=fingerprint:sha-256 $peerFingerprint\r\n" +
            "a=setup:passive\r\n" +
            "a=mid:0\r\n" +
            "a=candidate:0 1 udp 2130706431 $localIp ${udp.localPort} typ host generation 0\r\n"
          val body = answer.toByteArray()
          ex.responseHeaders.add("Location", "/whip/session1")
          ex.sendResponseHeaders(201, body.size.toLong())
          ex.responseBody.write(body)
        } else {
          ex.sendResponseHeaders(200, -1)
        }
      }
    }

    // GPX R65 — one reader thread for the life of the ingest, handing datagrams to the current
    // session's transport, so a second connect (a new offer) gets its own DTLS session on the same
    // UDP socket instead of racing the first session's reader for packets.
    @Volatile private var transport: DtlsTransport? = null
    private var reader: Thread? = null

    private fun startReader() {
      if (reader != null) return
      reader = Thread {
        val buf = ByteArray(2048)
        while (!closed) {
          val packet = DatagramPacket(buf, buf.size)
          try {
            udp.receive(packet)
          } catch (_: SocketException) {
            return@Thread
          }
          peerSocket.client = InetSocketAddress(packet.address, packet.port)
          val data = packet.data.copyOf(packet.length)
          val first = data[0].toInt() and 0xFF
          if (first in 20..63) {
            transport?.enqueue(data)
          } else if (first < 4 && data.size >= 20) {
            val id = data.copyOfRange(8, 20)
            val reply = StunCommand(
              StunHeader(HeaderType.SUCCESS, 0, Constants.MAGIC_COOKIE, id), listOf(),
              useIntegrity = false, useFingerprint = false
            ).toByteArray()
            udp.send(DatagramPacket(reply, reply.size, packet.socketAddress))
          }
        }
      }.apply { isDaemon = true; start() }
    }

    private fun startPeer(clientFingerprint: String) {
      runCatching { dtlsServer?.close() }
      val transport = DtlsTransport(peerSocket)
      this.transport = transport
      startReader()
      dtlsServer = DtlsServerConnection(cert, clientFingerprint)
      dtlsServer!!.start(transport, object : DtlsConnection.Callback {
        override fun onHandshakeComplete(keys: SrtpKeys) {}
        override fun onHandshakeFailed(reason: String?) {}
      })
    }

    fun close() {
      closed = true
      runCatching { dtlsServer?.close() }
      runCatching { udp.close() }
      runCatching { http.stop(0) }
    }
  }

  /** Holds the connect job inside `onConnectionSuccess` and, if asked, `disconnect()` inside `onDisconnect`. */
  private class GatedChecker : ConnectChecker {
    val successEntered = CountDownLatch(1)
    val releaseSuccess = CountDownLatch(1)
    val disconnectEntered = CountDownLatch(1)
    val releaseDisconnect = CountDownLatch(1)
    @Volatile var gateDisconnect = false
    val disconnects = AtomicInteger(0)
    val failures = CopyOnWriteArrayList<String>()

    // GPX R65 — every url `onConnectionStarted` was called with, in order, and the successes.
    val startedUrls = CopyOnWriteArrayList<String>()
    val successes = AtomicInteger(0)

    // GPX R65 — with true, only the first `onConnectionSuccess` waits on `releaseSuccess`; later
    // connects pass through. Lets a test hold one stale connect job while a later one streams.
    @Volatile var gateFirstSuccessOnly = false

    // GPX R65 — runs inside `onDisconnect`, on the stop's Main hop: the app's pattern of starting
    // the next connect from that callback.
    @Volatile var onDisconnectHook: (() -> Unit)? = null

    override fun onConnectionStarted(url: String) {
      startedUrls.add(url)
    }

    override fun onConnectionSuccess() {
      val n = successes.incrementAndGet()
      successEntered.countDown()
      if (gateFirstSuccessOnly && n > 1) return
      releaseSuccess.await(20, TimeUnit.SECONDS)
    }

    override fun onConnectionFailed(reason: String) {
      println("GPX R62 onConnectionFailed: $reason")
      failures.add(reason)
    }

    override fun onDisconnect() {
      disconnects.incrementAndGet()
      onDisconnectHook?.invoke()
      if (gateDisconnect) {
        disconnectEntered.countDown()
        releaseDisconnect.await(20, TimeUnit.SECONDS)
      }
    }

    override fun onAuthError() {}
    override fun onAuthSuccess() {}
  }

  private var ingest: FakeIngest? = null

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    // The client drops loopback candidates, so the fake ingest needs a non-loopback address. A VPN
    // tunnel (point-to-point) address cannot be sent to from a wildcard-bound socket, so skip those.
    val ip = java.net.NetworkInterface.getNetworkInterfaces().toList()
      .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
      .flatMap { nic -> nic.inetAddresses.toList().map { nic.name to it } }
      .filter { (_, a) ->
        a is java.net.Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.hostAddress!!.endsWith(".0")
      }
      .let { all -> all.firstOrNull { it.first == "en0" } ?: all.firstOrNull() }
      ?.second?.hostAddress
    println("GPX R62 fake ingest address: $ip")
    assumeTrue("no non-loopback IPv4 address on this machine", ip != null)
    ingest = FakeIngest(ip!!).also { it.start() }
  }

  @After
  fun tearDown() {
    ingest?.close()
    Dispatchers.resetMain()
  }

  private fun client(checker: ConnectChecker) = WhipClient(checker).apply {
    setOnlyAudio(true) // no SPS wait
  }

  private fun senderOf(client: WhipClient): WhipSender = field(client, "whipSender") as WhipSender

  private fun running(client: WhipClient): Boolean =
    BaseSender::class.java.getDeclaredField("running").apply { isAccessible = true }.getBoolean(senderOf(client))

  private fun connectJob(client: WhipClient): Job? = field(client, "job") as Job?

  private fun field(client: WhipClient, name: String): Any? =
    WhipClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client)

  private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
      assertTrue("timed out waiting for: $what", System.currentTimeMillis() < end)
      Thread.sleep(10)
    }
  }

  private fun await(what: String, latch: CountDownLatch) =
    assertTrue("timed out waiting for: $what", latch.await(20, TimeUnit.SECONDS))

  private fun stopLeakedSender(client: WhipClient) = runBlocking { senderOf(client).stop() }

  /**
   * GPX R62 — a stop that arrives after `onConnectionSuccess` has been called and before
   * `whipSender.start()` leaves no sender running, and a second `disconnect()` is harmless. Without
   * the fix the connect job starts the sender after the stop's sender-stop guard has already run, and
   * nothing stops it afterwards.
   */
  @Test
  fun `a stop between onConnectionSuccess and sender start leaves no sender running`() {
    val checker = GatedChecker().apply { gateDisconnect = true }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      await("onConnectionSuccess entered", checker.successEntered)

      // The stop begins: it reads isStreaming (true, so it would stop a running sender, but none is
      // running yet), closes the socket, sets isStreaming false and stops at onDisconnect.
      client.disconnect()
      await("onDisconnect entered", checker.disconnectEntered)
      assertFalse("the stop already set isStreaming false", client.isStreaming)
      assertFalse("no sender is running yet", running(client))
      // GPX R65 — the stop cancels the connect job at its very top, so the job is already cancelled
      // here, where it used to be still live (which is what let it start the sender after the guard).
      assertTrue("the stop cancelled the connect job first", connectJob(client)!!.isCancelled)

      // The connect job resumes from the callback, is found cancelled and never starts the sender.
      checker.releaseSuccess.countDown()
      waitUntil("connect job finished") { connectJob(client)!!.isCompleted }
      assertFalse("the cancelled job did not start the sender", running(client))

      // Let the stop finish.
      checker.releaseDisconnect.countDown()
      waitUntil("stop finished") { connectJob(client) == null }

      assertFalse("sender left running after the stop finished", running(client))
      assertFalse(client.isStreaming)

      // A second stop is harmless.
      val before = checker.disconnects.get()
      val scopeBefore = field(client, "scope")
      client.disconnect()
      waitUntil("second disconnect reached onDisconnect") { checker.disconnects.get() > before }
      // the last thing disconnect() does is replace its scope, so a new one means the stop is over
      waitUntil("second stop finished") { field(client, "scope") !== scopeBefore }
      assertFalse("sender running after the second disconnect", running(client))
      assertEquals(emptyList<String>(), checker.failures.toList())
    } finally {
      stopLeakedSender(client)
    }
  }

  /**
   * GPX R62 — the neighbouring case is safe. A stop that lands while `onConnectionSuccess` is still
   * running is seen: `withContext` throws the cancellation when the callback returns, so
   * `whipSender.start()` is never reached.
   */
  @Test
  fun `a stop while onConnectionSuccess is still running does not start the sender`() {
    val checker = GatedChecker()
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      await("onConnectionSuccess entered", checker.successEntered)

      client.disconnect()
      // disconnect() runs to job.cancelAndJoin(), which cancels the connect job and waits for it.
      waitUntil("connect job cancelled by the stop") { connectJob(client)?.isCancelled == true }

      checker.releaseSuccess.countDown()
      waitUntil("stop finished") { connectJob(client) == null }

      assertFalse("sender must not be running", running(client))
      assertFalse(client.isStreaming)
      assertEquals(emptyList<String>(), checker.failures.toList())
    } finally {
      stopLeakedSender(client)
    }
  }

  private fun assertStreaming(client: WhipClient, checker: GatedChecker) {
    waitUntil("sender running") { running(client) }
    assertTrue("isStreaming", client.isStreaming)
    assertEquals(emptyList<String>(), checker.failures.toList())
  }

  /**
   * GPX R65 — a `connect()` that lands inside a stop is deferred: the client remembers it and runs it
   * after the teardown, through the normal connect path, so it streams. The first connect job, held
   * inside `onConnectionSuccess`, is cancelled by the stop and never starts a sender, and the media
   * socket field ends up holding only the second connection's socket.
   */
  @Test
  fun `a connect during a stop is deferred and then streams`() {
    val checker = GatedChecker().apply { gateDisconnect = true }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      await("onConnectionSuccess entered", checker.successEntered)

      client.disconnect()
      await("onDisconnect entered", checker.disconnectEntered)
      assertFalse("the stop already set isStreaming false", client.isStreaming)

      client.connect(ingest!!.url)
      assertFalse("a deferred connect is not streaming yet", client.isStreaming)
      assertEquals("a deferred connect has not started", 1, checker.startedUrls.size)

      // the held first job and the stop go on; only the second connect may stream
      checker.releaseSuccess.countDown()
      checker.releaseDisconnect.countDown()
      waitUntil("the deferred connect started") { checker.startedUrls.size == 2 }
      assertStreaming(client, checker)
      assertEquals("the stop raised onDisconnect once", 1, checker.disconnects.get())
      assertTrue("the second connection's socket is the one held", field(client, "iceSocket") != null)

      // and it stops cleanly
      checker.gateDisconnect = false
      client.disconnect()
      waitUntil("second stop finished") { checker.disconnects.get() == 2 && !client.isStreaming }
      assertFalse(running(client))
      assertEquals("no socket is held after the last stop", null, field(client, "iceSocket"))
    } finally {
      stopLeakedSender(client)
    }
  }

  /** GPX R65 — two connects while stopping: the latest url runs, once. */
  @Test
  fun `two connects during a stop run only the latest`() {
    val checker = GatedChecker().apply {
      gateDisconnect = true
      releaseSuccess.countDown()
    }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.disconnect()
      await("onDisconnect entered", checker.disconnectEntered)
      client.connect(ingest!!.url + "/first")
      client.connect(ingest!!.url + "/latest")
      checker.releaseDisconnect.countDown()

      waitUntil("the deferred connect started") { checker.startedUrls.size == 2 }
      assertStreaming(client, checker)
      Thread.sleep(300)
      assertEquals(listOf(ingest!!.url, ingest!!.url + "/latest"), checker.startedUrls.toList())
    } finally {
      stopLeakedSender(client)
    }
  }

  /** GPX R65 — a connect deferred by a stop, then a stop: the stop wins, nothing starts. */
  @Test
  fun `a deferred connect then a disconnect starts nothing`() {
    val checker = GatedChecker().apply {
      gateDisconnect = true
      releaseSuccess.countDown()
    }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.disconnect()
      await("onDisconnect entered", checker.disconnectEntered)
      client.connect(ingest!!.url + "/deferred")
      client.disconnect() // the stop wins: this drops the deferred connect
      checker.releaseDisconnect.countDown()

      waitUntil("both stops finished") { checker.disconnects.get() == 2 }
      Thread.sleep(500)
      assertEquals("the deferred connect was dropped", listOf(ingest!!.url), checker.startedUrls.toList())
      assertFalse(client.isStreaming)
      assertFalse(running(client))
      assertEquals(emptyList<String>(), checker.failures.toList())
    } finally {
      stopLeakedSender(client)
    }
  }

  /** GPX R65 — the app's pattern: the next connect is started from inside `onDisconnect`. */
  @Test
  fun `a connect called from inside onDisconnect runs after the stop`() {
    val checker = GatedChecker().apply { releaseSuccess.countDown() }
    val client = client(checker)
    try {
      checker.onDisconnectHook = {
        checker.onDisconnectHook = null
        client.connect(ingest!!.url + "/again")
      }
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.disconnect()
      waitUntil("the connect from onDisconnect started") { checker.startedUrls.size == 2 }
      assertStreaming(client, checker)
      assertEquals(ingest!!.url + "/again", checker.startedUrls[1])
      assertEquals(1, checker.disconnects.get())
    } finally {
      stopLeakedSender(client)
    }
  }

  /** GPX R65 — `disconnect()` then `connect()` back to back on one thread, no gate: it streams. */
  @Test
  fun `a stop followed at once by a start streams`() {
    val checker = GatedChecker().apply { releaseSuccess.countDown() }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.disconnect()
      client.connect(ingest!!.url + "/next")

      waitUntil("the second connect started") { checker.startedUrls.size == 2 }
      assertStreaming(client, checker)
      assertEquals(1, checker.disconnects.get())
    } finally {
      stopLeakedSender(client)
    }
  }

  /**
   * GPX R65 — a connect job stuck longer than the stop's capped wait does not hang the stop, and when
   * it wakes it writes nothing: the connect that follows keeps streaming.
   */
  @Test
  fun `a connect job that outlives the stop writes nothing`() {
    val checker = GatedChecker().apply { gateFirstSuccessOnly = true }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      await("onConnectionSuccess entered", checker.successEntered) // held, and stays held

      val started = System.currentTimeMillis()
      client.disconnect()
      client.connect(ingest!!.url + "/next")
      waitUntil("the connect after the stop started", 20_000) { checker.startedUrls.size == 2 }
      assertTrue("the stop was not held for the stuck job", System.currentTimeMillis() - started < 10_000)
      assertStreaming(client, checker)

      // the stuck job wakes up: cancelled and stale, it must not touch the live connection
      checker.releaseSuccess.countDown()
      Thread.sleep(500)
      assertStreaming(client, checker)
      assertEquals(1, checker.disconnects.get())
    } finally {
      checker.releaseSuccess.countDown()
      stopLeakedSender(client)
    }
  }

  /** GPX R65 — a second `disconnect()` waits for the first teardown: only one runs at a time. */
  @Test
  fun `two disconnects do not interleave`() {
    val checker = GatedChecker().apply {
      gateDisconnect = true
      releaseSuccess.countDown()
    }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.disconnect()
      await("first onDisconnect entered", checker.disconnectEntered)
      client.disconnect()
      Thread.sleep(500)
      assertEquals("the second teardown waits for the first", 1, checker.disconnects.get())

      checker.releaseDisconnect.countDown()
      waitUntil("both teardowns finished") { checker.disconnects.get() == 2 }
      Thread.sleep(200)
      assertFalse(client.isStreaming)
      assertFalse(running(client))
      assertEquals(emptyList<String>(), checker.failures.toList())
    } finally {
      stopLeakedSender(client)
    }
  }

  /** GPX R65 — the retry path: `reConnect` stops, waits, then connects again with the same callbacks. */
  @Test
  fun `a retry stops and connects again`() {
    val checker = GatedChecker().apply { releaseSuccess.countDown() }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.reConnect(200)
      waitUntil("the retry connect started") { checker.startedUrls.size == 2 }
      assertStreaming(client, checker)
      assertEquals("a retry's teardown raises no onDisconnect", 0, checker.disconnects.get())
    } finally {
      stopLeakedSender(client)
    }
  }

  /** GPX R65 — a user stop during a retry's wait between its stop and its connect: nothing connects. */
  @Test
  fun `a user stop during a retry backoff cancels the retry connect`() {
    val checker = GatedChecker().apply { releaseSuccess.countDown() }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.reConnect(1_500)
      waitUntil("the retry's teardown stopped the sender") { !running(client) }
      client.disconnect()
      waitUntil("the stop finished") { checker.disconnects.get() == 1 }
      Thread.sleep(2_000)

      assertEquals("the retry connect never ran", 1, checker.startedUrls.size)
      assertFalse(client.isStreaming)
      assertFalse(running(client))
      assertEquals(emptyList<String>(), checker.failures.toList())
    } finally {
      stopLeakedSender(client)
    }
  }

  /** GPX R65 — `reConnect` arriving while a user stop runs does nothing. */
  @Test
  fun `a retry that arrives during a stop does not start`() {
    val checker = GatedChecker().apply {
      gateDisconnect = true
      releaseSuccess.countDown()
    }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.disconnect()
      await("onDisconnect entered", checker.disconnectEntered)
      client.reConnect(100)
      checker.releaseDisconnect.countDown()
      waitUntil("stop finished") { !client.isStreaming && field(client, "job") == null }
      Thread.sleep(800)

      assertEquals(1, checker.startedUrls.size)
      assertFalse(client.isStreaming)
      assertFalse(running(client))
    } finally {
      stopLeakedSender(client)
    }
  }

  /** GPX R62 — an ordinary connect and stop still stops the running sender, and reports nothing. */
  @Test
  fun `a connect then a stop stops the running sender`() {
    val checker = GatedChecker().apply { releaseSuccess.countDown() }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("sender running after connect") { running(client) }

      client.disconnect()
      waitUntil("stop finished") { connectJob(client) == null && checker.disconnects.get() == 1 }

      assertFalse("sender must not be running", running(client))
      assertFalse(client.isStreaming)
      assertEquals(emptyList<String>(), checker.failures.toList())
    } finally {
      stopLeakedSender(client)
    }
  }

  /** GPX R62 — a real connect error is still reported once, and the stop that follows adds no second report. */
  @Test
  fun `a real connect error is reported once`() {
    val checker = GatedChecker()
    val client = client(checker)
    try {
      // nothing listens on port 1, so the offer fails
      client.connect("http://127.0.0.1:1/whip")
      waitUntil("connect error reported") { checker.failures.isNotEmpty() }

      client.disconnect()
      waitUntil("stop finished") { checker.disconnects.get() == 1 }
      Thread.sleep(300)

      assertEquals(1, checker.failures.size)
      assertTrue(checker.failures[0], checker.failures[0].startsWith("Error configure stream, offer failed"))
      assertFalse(running(client))
    } finally {
      stopLeakedSender(client)
    }
  }
}
