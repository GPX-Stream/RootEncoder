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

    private fun startPeer(clientFingerprint: String) {
      val transport = DtlsTransport(peerSocket)
      Thread {
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
            transport.enqueue(data)
          } else if (first < 4 && data.size >= 20) {
            val id = data.copyOfRange(8, 20)
            val reply = StunCommand(
              StunHeader(HeaderType.SUCCESS, 0, Constants.MAGIC_COOKIE, id), listOf(),
              useIntegrity = false, useFingerprint = false
            ).toByteArray()
            udp.send(DatagramPacket(reply, reply.size, packet.socketAddress))
          }
        }
      }.apply { isDaemon = true }.start()
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

    // GPX R65 — holds the second connect job inside `onConnectionStarted`, so that job is still in
    // flight while the stop runs (see the overlapping-connect test).
    val startedCount = AtomicInteger(0)
    val secondStartedEntered = CountDownLatch(1)
    val releaseSecondStarted = CountDownLatch(1)
    @Volatile var gateSecondStarted = false

    override fun onConnectionStarted(url: String) {
      if (startedCount.incrementAndGet() == 2 && gateSecondStarted) {
        secondStartedEntered.countDown()
        releaseSecondStarted.await(20, TimeUnit.SECONDS)
      }
    }

    override fun onConnectionSuccess() {
      successEntered.countDown()
      releaseSuccess.await(20, TimeUnit.SECONDS)
    }

    override fun onConnectionFailed(reason: String) {
      println("GPX R62 onConnectionFailed: $reason")
      failures.add(reason)
    }

    override fun onDisconnect() {
      disconnects.incrementAndGet()
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
      assertFalse("the connect job was not cancelled yet", connectJob(client)!!.isCancelled)

      // The connect job resumes (not cancelled), runs the lines after onConnectionSuccess and starts
      // the sender.
      checker.releaseSuccess.countDown()
      waitUntil("connect job finished") { connectJob(client)!!.isCompleted }

      // Let the stop finish.
      checker.releaseDisconnect.countDown()
      waitUntil("stop finished") { connectJob(client) == null }

      // The connect job did run on to start(): the stop must stop that sender too.
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

  /**
   * GPX R65 — DOCUMENTS A DEFECT; the fix is not in this change (design: fork plan, R65). A `connect()`
   * that lands inside a stop, after `disconnect(true)` has set `isStreaming` false and before it
   * replaces its scope (the window runs to that last line, not only to the join), is taken as a fresh
   * connect: it launches a second connect job and overwrites the `job` field. The stop then (1)
   * cancels and joins only the second job, so it finishes while the first job is still running, and
   * (2) leaves `isStreaming` true with no connect job and nothing reported, so the next `connect()`
   * is ignored until a `disconnect()`. A `connect()` after the join, before the scope is replaced,
   * launches on a scope that is about to be cancelled and dies the same way. The cancelled second
   * job's media socket is not closed until the next `disconnect()`.
   *
   * The assertions say what happens today, and this test FAILS once the defect is fixed (under fix A,
   * the join of every job, by timing out on "stop finished", because the stop then waits for the first
   * job, which this test still holds in `onConnectionSuccess`). Rewrite it together with the fix.
   *
   * Not shown here: the first job starting a sender after the stop. The stop's last step cancels the
   * scope, and a job that returns from `onConnectionSuccess` after that is cancelled before
   * `whipSender.start()`, so only a job that is on the CPU between the stop's second sender stop and
   * its scope cancel could start one, which no gate can place.
   */
  @Test
  fun `a connect during a stop leaves isStreaming true and does not await the first job`() {
    val checker = GatedChecker().apply {
      gateDisconnect = true
      gateSecondStarted = true
    }
    val client = client(checker)
    try {
      client.connect(ingest!!.url)
      await("onConnectionSuccess entered", checker.successEntered)
      val firstJob = connectJob(client)!!

      val scopeAtStop = field(client, "scope")
      client.disconnect()
      await("onDisconnect entered", checker.disconnectEntered)
      assertFalse("the stop already set isStreaming false", client.isStreaming)

      // The overlapping connect: the guard `!isStreaming` lets it in and it overwrites `job`.
      client.connect(ingest!!.url)
      await("second connect job entered onConnectionStarted", checker.secondStartedEntered)
      val secondJob = connectJob(client)!!
      assertTrue("the second connect replaced the job field", secondJob !== firstJob)
      assertTrue(client.isStreaming)

      // The stop resumes and cancels the job it finds in the field: the second one.
      checker.releaseDisconnect.countDown()
      waitUntil("stop cancelled the second job") { secondJob.isCancelled }
      checker.releaseSecondStarted.countDown()
      // the stop's last step replaces its scope, after the job field is cleared and the tail stop ran
      waitUntil("stop finished") { field(client, "scope") !== scopeAtStop }

      assertFalse("DEFECT: the stop finished while the first connect job was still running", firstJob.isCompleted)
      assertTrue("DEFECT: isStreaming is true after the stop, with no connect job", client.isStreaming)
      assertEquals("the cancelled second connect reported nothing", emptyList<String>(), checker.failures.toList())

      // Because isStreaming is stuck true, a new connect() is ignored.
      client.connect(ingest!!.url)
      Thread.sleep(300)
      assertEquals("DEFECT: the new connect() was ignored", 2, checker.startedCount.get())

      // The first job, cancelled by the stop's scope cancel, ends without starting a sender.
      checker.releaseSuccess.countDown()
      waitUntil("first job finished") { firstJob.isCompleted }
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
