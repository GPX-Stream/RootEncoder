package com.pedro.rtmp.rtmp

import com.pedro.common.ConnectChecker
import com.pedro.common.base.BaseSender
import com.pedro.rtmp.FakeRtmpSocket
import com.pedro.rtmp.amf.AmfNull
import com.pedro.rtmp.amf.AmfNumber
import com.pedro.rtmp.amf.AmfObject
import com.pedro.rtmp.rtmp.chunk.ChunkStreamId
import com.pedro.rtmp.rtmp.chunk.ChunkType
import com.pedro.rtmp.rtmp.message.BasicHeader
import com.pedro.rtmp.rtmp.message.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * GPX R64 — whether a stop that lands between `onConnectionSuccess` and `rtmpSender.start()` leaves a
 * sender running (fork plan, R64). This drives the real [RtmpClient] against a fake ingest on a
 * loopback TCP port: it completes the RTMP handshake, then answers the client's `connect` and
 * `createStream` with `_result` and its `publish` with `onStatus NetStream.Publish.Start`. The
 * answers are built with the fork's own [Command] class, serialised through [FakeRtmpSocket].
 *
 * The stop is placed with the [ConnectChecker], as in the WHIP test of R62: `disconnect(true)` calls
 * `onDisconnect` after it has read `isStreaming` for the sender-stop guard and before it cancels the
 * connect job. Holding the connect job inside `onConnectionSuccess` and `disconnect()` inside
 * `onDisconnect` puts the stop exactly in the window, with no timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RtmpConnectStopRaceTest {

  /** One RTMP ingest: handshake, then the three answers that take the client to "publishing". */
  private class FakeIngest {
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    val url get() = "rtmp://127.0.0.1:${server.localPort}/app/stream"

    // GPX R65 — serves every connection that arrives, one thread each, so a client that connects a
    // second time (a deferred connect, a retry) gets its own session.
    private val worker = thread(isDaemon = true) {
      try {
        while (true) {
          val client = server.accept()
          thread(isDaemon = true) {
            try {
              serve(client)
            } catch (_: Exception) {
              // closed by the client or by close()
            }
          }
        }
      } catch (_: Exception) {
        // closed by close()
      }
    }

    private fun InputStream.readFully(size: Int): ByteArray {
      val bytes = ByteArray(size)
      var done = 0
      while (done < size) {
        val n = read(bytes, done, size - done)
        if (n < 0) throw java.io.IOException("closed")
        done += n
      }
      return bytes
    }

    private fun serve(client: java.net.Socket) {
      val input = client.getInputStream()
      val output = client.getOutputStream()
      val c0c1 = input.readFully(1537)
      output.write(3)
      output.write(ByteArray(1536) { it.toByte() }) // S1
      output.write(c0c1, 1, 1536) // S2 echoes C1
      output.flush()
      input.readFully(1536) // C2
      input.read() // the first byte of the client's `connect`
      output.write(answers())
      output.flush()
      val sink = ByteArray(4096)
      while (input.read(sink) >= 0) {
        // the client's createStream, publish, metadata and anything it sends later
      }
    }

    private fun answers(): ByteArray = runBlocking {
      val socket = FakeRtmpSocket()
      val connectResult = Command("_result", 1, 0, 0,
        BasicHeader(ChunkType.TYPE_0, ChunkStreamId.OVER_CONNECTION.mark))
      connectResult.addData(AmfNull())
      // the client numbers its commands connect 1, releaseStream 2, FCPublish 3, createStream 4
      val createStreamResult = Command("_result", 4, 0, 0,
        BasicHeader(ChunkType.TYPE_0, ChunkStreamId.OVER_CONNECTION.mark))
      createStreamResult.addData(AmfNull())
      createStreamResult.addData(AmfNumber(1.0))
      val status = Command("onStatus", 0, 0, 1,
        BasicHeader(ChunkType.TYPE_0, ChunkStreamId.OVER_STREAM.mark))
      status.addData(AmfNull())
      status.addData(AmfObject().apply { setProperty("code", "NetStream.Publish.Start") })
      listOf(connectResult, createStreamResult, status).forEach {
        it.writeHeader(socket)
        it.writeBody(socket, 128)
      }
      socket.output.toByteArray()
    }

    fun close() {
      runCatching { server.close() }
      worker.join(1_000)
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

    // GPX R65 — runs inside `onDisconnect`: the app's pattern of starting the next connect from it.
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
      println("GPX R64 onConnectionFailed: $reason")
      failures.add(reason)
    }

    override fun onNewBitrate(bitrate: Long) {}

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
    ingest = FakeIngest()
  }

  @After
  fun tearDown() {
    ingest?.close()
    Dispatchers.resetMain()
  }

  private fun field(client: RtmpClient, name: String): Any? =
    RtmpClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client)

  private fun senderOf(client: RtmpClient): RtmpSender = field(client, "rtmpSender") as RtmpSender

  private fun running(client: RtmpClient): Boolean =
    BaseSender::class.java.getDeclaredField("running").apply { isAccessible = true }.getBoolean(senderOf(client))

  private fun connectJob(client: RtmpClient): Job? = field(client, "job") as Job?

  private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
      assertTrue("timed out waiting for: $what", System.currentTimeMillis() < end)
      Thread.sleep(10)
    }
  }

  private fun await(what: String, latch: CountDownLatch) =
    assertTrue("timed out waiting for: $what", latch.await(20, TimeUnit.SECONDS))

  private fun stopLeakedSender(client: RtmpClient) = runBlocking { senderOf(client).stop() }

  /**
   * GPX R64 — a stop that arrives after `onConnectionSuccess` has been called and before
   * `rtmpSender.start()` leaves no sender running, and a second `disconnect()` is harmless. Without
   * the fix the connect job starts the sender after the stop's sender-stop guard has already run, and
   * nothing stops it afterwards.
   */
  @Test
  fun `a stop between onConnectionSuccess and sender start leaves no sender running`() {
    val checker = GatedChecker().apply { gateDisconnect = true }
    val client = RtmpClient(checker)
    try {
      client.connect(ingest!!.url)
      await("onConnectionSuccess entered", checker.successEntered)

      // The stop begins: it reads isStreaming (true, so it would stop a running sender, but none is
      // running yet), closes the socket, sets isStreaming false and stops at onDisconnect.
      val scopeAtStop = field(client, "scope")
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

      // Let the stop finish. The stop's last step replaces its scope, after the tail sender stop, so
      // a new scope means the stop is over. The job field is cleared before that tail stop.
      checker.releaseDisconnect.countDown()
      waitUntil("stop finished") { field(client, "scope") !== scopeAtStop }

      assertFalse("sender left running after the stop finished", running(client))
      assertFalse(client.isStreaming)

      // A second stop is harmless.
      val before = checker.disconnects.get()
      val scopeBefore = field(client, "scope")
      client.disconnect()
      waitUntil("second disconnect reached onDisconnect") { checker.disconnects.get() > before }
      // disconnect() replaces its scope before it resets the command state, so a new scope means the
      // stop is nearly over; the sender check below is the one that matters.
      waitUntil("second stop finished") { field(client, "scope") !== scopeBefore }
      assertFalse("sender running after the second disconnect", running(client))
      assertEquals(emptyList<String>(), checker.failures.toList())
    } finally {
      stopLeakedSender(client)
    }
  }

  /**
   * GPX R64 — the neighbouring case is safe. A stop that lands while `onConnectionSuccess` is still
   * running is seen: `withContext` throws the cancellation when the callback returns, so
   * `rtmpSender.start()` is never reached.
   */
  @Test
  fun `a stop while onConnectionSuccess is still running does not start the sender`() {
    val checker = GatedChecker()
    val client = RtmpClient(checker)
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

  private fun assertStreaming(client: RtmpClient, checker: GatedChecker) {
    waitUntil("sender running") { running(client) }
    assertTrue("isStreaming", client.isStreaming)
    assertEquals(emptyList<String>(), checker.failures.toList())
  }

  /**
   * GPX R65 — a `connect()` that lands inside a stop is deferred: the client remembers it and runs it
   * after the teardown, through the normal connect path, so it streams. The first connect job, held
   * inside `onConnectionSuccess`, is cancelled by the stop and never starts a sender, and the socket
   * field ends up holding only the second connection's socket.
   */
  @Test
  fun `a connect during a stop is deferred and then streams`() {
    val checker = GatedChecker().apply { gateDisconnect = true }
    val client = RtmpClient(checker)
    try {
      client.connect(ingest!!.url)
      await("onConnectionSuccess entered", checker.successEntered)
      val firstSocket = field(client, "socket")

      client.disconnect()
      await("onDisconnect entered", checker.disconnectEntered)
      assertFalse("the stop already set isStreaming false", client.isStreaming)

      client.connect(ingest!!.url)
      assertFalse("a deferred connect is not streaming yet", client.isStreaming)
      assertEquals("a deferred connect has not started", 1, checker.startedUrls.size)

      checker.releaseSuccess.countDown()
      checker.releaseDisconnect.countDown()
      waitUntil("the deferred connect started") { checker.startedUrls.size == 2 }
      assertStreaming(client, checker)
      assertEquals("the stop raised onDisconnect once", 1, checker.disconnects.get())
      assertTrue("the second connection has its own socket", field(client, "socket") !== firstSocket)

      checker.gateDisconnect = false
      client.disconnect()
      waitUntil("second stop finished") { checker.disconnects.get() == 2 && !client.isStreaming }
      assertFalse(running(client))
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
    val client = RtmpClient(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.disconnect()
      await("onDisconnect entered", checker.disconnectEntered)
      client.connect(ingest!!.url + "?n=first")
      client.connect(ingest!!.url + "?n=latest")
      checker.releaseDisconnect.countDown()

      waitUntil("the deferred connect started") { checker.startedUrls.size == 2 }
      assertStreaming(client, checker)
      Thread.sleep(300)
      assertEquals(listOf(ingest!!.url, ingest!!.url + "?n=latest"), checker.startedUrls.toList())
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
    val client = RtmpClient(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.disconnect()
      await("onDisconnect entered", checker.disconnectEntered)
      client.connect(ingest!!.url + "?n=deferred")
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
    val client = RtmpClient(checker)
    try {
      checker.onDisconnectHook = {
        checker.onDisconnectHook = null
        client.connect(ingest!!.url + "?n=again")
      }
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.disconnect()
      waitUntil("the connect from onDisconnect started") { checker.startedUrls.size == 2 }
      assertStreaming(client, checker)
      assertEquals(ingest!!.url + "?n=again", checker.startedUrls[1])
      assertEquals(1, checker.disconnects.get())
    } finally {
      stopLeakedSender(client)
    }
  }

  /** GPX R65 — `disconnect()` then `connect()` back to back on one thread, no gate: it streams. */
  @Test
  fun `a stop followed at once by a start streams`() {
    val checker = GatedChecker().apply { releaseSuccess.countDown() }
    val client = RtmpClient(checker)
    try {
      client.connect(ingest!!.url)
      waitUntil("running") { running(client) }

      client.disconnect()
      client.connect(ingest!!.url + "?n=next")

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
    val client = RtmpClient(checker)
    try {
      client.connect(ingest!!.url)
      await("onConnectionSuccess entered", checker.successEntered) // held, and stays held

      val started = System.currentTimeMillis()
      client.disconnect()
      client.connect(ingest!!.url + "?n=next")
      waitUntil("the connect after the stop started", 20_000) { checker.startedUrls.size == 2 }
      assertTrue("the stop was not held for the stuck job", System.currentTimeMillis() - started < 10_000)
      assertStreaming(client, checker)

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
    val client = RtmpClient(checker)
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
    val client = RtmpClient(checker)
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
    val client = RtmpClient(checker)
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
    val client = RtmpClient(checker)
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

  /** GPX R64 — an ordinary connect and stop still stops the running sender, and reports nothing. */
  @Test
  fun `a connect then a stop stops the running sender`() {
    val checker = GatedChecker().apply { releaseSuccess.countDown() }
    val client = RtmpClient(checker)
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

  /** GPX R64 — a real connect error is reported once, and the stop that follows adds no second report. */
  @Test
  fun `a real connect error is reported once`() {
    val checker = GatedChecker()
    val client = RtmpClient(checker)
    try {
      // nothing listens on port 1, so the TCP connect is refused
      client.connect("rtmp://127.0.0.1:1/app/stream")
      waitUntil("connect error reported") { checker.failures.isNotEmpty() }

      client.disconnect()
      waitUntil("stop finished") { checker.disconnects.get() == 1 }
      Thread.sleep(300)

      assertEquals(1, checker.failures.size)
      assertTrue(checker.failures[0], checker.failures[0].startsWith("Error configure stream"))
      assertFalse(running(client))
    } finally {
      stopLeakedSender(client)
    }
  }
}
