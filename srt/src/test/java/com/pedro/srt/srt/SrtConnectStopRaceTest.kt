package com.pedro.srt.srt

import com.pedro.common.ConnectChecker
import com.pedro.common.base.BaseSender
import com.pedro.srt.srt.packets.control.handshake.Handshake
import com.pedro.srt.srt.packets.control.handshake.HandshakeType
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * GPX R63 — whether a stop that lands between `onConnectionSuccess` and `srtSender.start()` leaves a
 * sender running (fork plan, R63). This drives the real [SrtClient] against a loopback UDP server that
 * completes the SRT handshake, the way `SrtInboundSilenceTest` does.
 *
 * The stop is placed with the [ConnectChecker], as in the WHIP test of R62: `disconnect(true)` calls
 * `onDisconnect` after it has read `isStreaming` for the sender-stop guard and before it cancels the
 * connect job. Holding the connect job inside `onConnectionSuccess` and `disconnect()` inside
 * `onDisconnect` puts the stop exactly in the window, with no timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SrtConnectStopRaceTest {

  /** Answers every handshake with a CONCLUSION and says nothing else. */
  private class LoopbackServer {
    val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val worker = thread(isDaemon = true) {
      val buf = ByteArray(2048)
      while (running.get()) {
        val packet = DatagramPacket(buf, buf.size)
        try {
          socket.receive(packet)
        } catch (_: Exception) {
          break
        }
        if ((buf[0].toInt() and 0xFF) == 0x80 && buf[1].toInt() == 0) {
          val reply = Handshake(handshakeType = HandshakeType.CONCLUSION, srtSocketId = 77)
          reply.write(0, 0)
          val data = reply.getData()
          socket.send(DatagramPacket(data, data.size, packet.socketAddress))
        }
      }
    }

    val url get() = "srt://127.0.0.1:${socket.localPort}/test?latency=120"

    fun close() {
      running.set(false)
      socket.close()
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

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
      successEntered.countDown()
      releaseSuccess.await(20, TimeUnit.SECONDS)
    }

    override fun onConnectionFailed(reason: String) {
      println("GPX R63 onConnectionFailed: $reason")
      failures.add(reason)
    }

    override fun onNewBitrate(bitrate: Long) {}

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

  private var server: LoopbackServer? = null

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    server = LoopbackServer()
  }

  @After
  fun tearDown() {
    server?.close()
    Dispatchers.resetMain()
  }

  private fun field(client: SrtClient, name: String): Any? =
    SrtClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client)

  private fun senderOf(client: SrtClient): SrtSender = field(client, "srtSender") as SrtSender

  private fun running(client: SrtClient): Boolean =
    BaseSender::class.java.getDeclaredField("running").apply { isAccessible = true }.getBoolean(senderOf(client))

  private fun connectJob(client: SrtClient): Job? = field(client, "job") as Job?

  private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
      assertTrue("timed out waiting for: $what", System.currentTimeMillis() < end)
      Thread.sleep(10)
    }
  }

  private fun await(what: String, latch: CountDownLatch) =
    assertTrue("timed out waiting for: $what", latch.await(20, TimeUnit.SECONDS))

  private fun stopLeakedSender(client: SrtClient) = runBlocking { senderOf(client).stop() }

  /**
   * GPX R63 — a stop that arrives after `onConnectionSuccess` has been called and before
   * `srtSender.start()` leaves no sender running, and a second `disconnect()` is harmless. Without the
   * fix the connect job starts the sender after the stop's sender-stop guard has already run, and
   * nothing stops it afterwards.
   */
  @Test
  fun `a stop between onConnectionSuccess and sender start leaves no sender running`() {
    val checker = GatedChecker().apply { gateDisconnect = true }
    val client = SrtClient(checker)
    try {
      client.connect(server!!.url)
      await("onConnectionSuccess entered", checker.successEntered)

      // The stop begins: it reads isStreaming (true, so it would stop a running sender, but none is
      // running yet), closes the socket, sets isStreaming false and stops at onDisconnect.
      val scopeAtStop = field(client, "scope")
      client.disconnect()
      await("onDisconnect entered", checker.disconnectEntered)
      assertFalse("the stop already set isStreaming false", client.isStreaming)
      assertFalse("no sender is running yet", running(client))
      assertFalse("the connect job was not cancelled yet", connectJob(client)!!.isCancelled)

      // The connect job resumes (not cancelled), runs the lines after onConnectionSuccess and starts
      // the sender.
      checker.releaseSuccess.countDown()
      waitUntil("connect job finished") { connectJob(client)!!.isCompleted }
      assertTrue("the race was driven: the connect job started the sender after the stop's guard", running(client))

      // Let the stop finish. The stop's last step replaces its scope, after the tail sender stop, so
      // a new scope means the stop is over. The job field is cleared before that tail stop.
      checker.releaseDisconnect.countDown()
      waitUntil("stop finished") { field(client, "scope") !== scopeAtStop }

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
   * GPX R63 — the neighbouring case is safe. A stop that lands while `onConnectionSuccess` is still
   * running is seen: `withContext` throws the cancellation when the callback returns, so
   * `srtSender.start()` is never reached.
   */
  @Test
  fun `a stop while onConnectionSuccess is still running does not start the sender`() {
    val checker = GatedChecker()
    val client = SrtClient(checker)
    try {
      client.connect(server!!.url)
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

  /** GPX R63 — an ordinary connect and stop still stops the running sender, and reports nothing. */
  @Test
  fun `a connect then a stop stops the running sender`() {
    val checker = GatedChecker().apply { releaseSuccess.countDown() }
    val client = SrtClient(checker)
    try {
      client.connect(server!!.url)
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

  /** GPX R63 — a real connect error is reported once, and the stop that follows adds no second report. */
  @Test
  fun `a real connect error is reported once`() {
    val checker = GatedChecker()
    val client = SrtClient(checker)
    try {
      // a malformed endpoint (no stream id) fails before any socket is opened
      client.connect("srt://127.0.0.1:9")
      waitUntil("connect error reported") { checker.failures.isNotEmpty() }

      client.disconnect()
      waitUntil("stop finished") { checker.disconnects.get() == 1 }
      Thread.sleep(300)

      assertEquals(1, checker.failures.size)
      assertTrue(checker.failures[0], checker.failures[0].startsWith("Endpoint malformed"))
      assertFalse(running(client))
    } finally {
      stopLeakedSender(client)
    }
  }
}
