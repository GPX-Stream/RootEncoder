package com.pedro.srt.srt

import com.pedro.common.ConnectChecker
import com.pedro.srt.srt.packets.control.handshake.Handshake
import com.pedro.srt.srt.packets.control.handshake.HandshakeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * GPX R7, R60 — [SrtClient] exposes the inbound-silence reading and fails no connection on it; a new
 * connect clears the previous connection's reading.
 *
 * A loopback UDP server stands in for the peer. It completes the handshake and then stays silent
 * (the blackhole case), or never answers at all. Time is driven through [SrtClient.nowMs] as real
 * elapsed time plus an offset, so a "6 s silence" costs no real wait beyond the client's own loops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SrtInboundSilenceTest {

  /** Answers every handshake with a CONCLUSION, then says nothing. Counts what it received. */
  private class LoopbackServer(answerHandshakes: Boolean) {
    @Volatile var answer = answerHandshakes
    val socket = DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1"))
    val received = AtomicInteger(0)
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
        received.incrementAndGet()
        if (answer && isHandshake(buf)) {
          val reply = Handshake(handshakeType = HandshakeType.CONCLUSION, srtSocketId = 77)
          reply.write(0, 0)
          val data = reply.getData()
          socket.send(DatagramPacket(data, data.size, packet.socketAddress))
        }
      }
    }
    /** A handshake is a control packet (top bit set) of control type 0. */
    private fun isHandshake(b: ByteArray) = (b[0].toInt() and 0xFF) == 0x80 && b[1].toInt() == 0

    val url get() = "srt://127.0.0.1:${socket.localPort}/test?latency=120"
    fun close() {
      running.set(false)
      socket.close()
      worker.join(1_000)
    }
  }

  private class Recorder : ConnectChecker {
    val failures = CopyOnWriteArrayList<String>()
    val successes = AtomicInteger(0)
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { successes.incrementAndGet() }
    override fun onConnectionFailed(reason: String) { failures.add(reason) }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {}
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
  }

  private val servers = mutableListOf<LoopbackServer>()
  private val offsetMs = AtomicLong(0)
  private var client: SrtClient? = null

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    client?.disconnect()
    Thread.sleep(300)
    servers.forEach { it.close() }
    Dispatchers.resetMain()
  }

  private fun server(answer: Boolean) = LoopbackServer(answer).also { servers.add(it) }

  private fun newClient(checker: ConnectChecker): SrtClient {
    val c = SrtClient(checker)
    c.nowMs = { System.nanoTime() / 1_000_000 + offsetMs.get() }
    client = c
    return c
  }

  private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < end) Thread.sleep(20)
    assertTrue("condition not reached within ${timeoutMs}ms", condition())
  }

  @Test
  fun `a silent server is not reported dead by the client, and the reading keeps climbing`() {
    val srv = server(answer = true)
    val checker = Recorder()
    val c = newClient(checker)
    c.connect(srv.url)
    waitUntil(5_000) { checker.successes.get() == 1 }
    srv.answer = false // from here the peer is silent, so nothing re-stamps the reading
    waitUntil(2_000) { c.getInboundSilenceMs() >= 0 }

    // The server now sends nothing. Move the clock 6 s past the old 5.5 s limit and give the
    // old one-second check more than one tick to act.
    offsetMs.addAndGet(6_000)
    Thread.sleep(2_500)

    assertTrue("the reading climbs past 5.5 s", c.getInboundSilenceMs() >= 6_000)
    assertEquals("the client reports nothing on silence", emptyList<String>(), checker.failures.toList())
    assertTrue(c.isStreaming)
  }

  @Test
  fun `a reconnect reports no silence until its own handshake completes`() {
    val first = server(answer = true)
    val mute = server(answer = false)
    val checker = Recorder()
    val c = newClient(checker)
    c.connect(first.url)
    waitUntil(5_000) { checker.successes.get() == 1 }
    first.answer = false
    offsetMs.addAndGet(30_000)
    waitUntil(2_000) { c.getInboundSilenceMs() >= 30_000 }

    c.reConnect(0, mute.url)
    waitUntil(5_000) { mute.received.get() > 0 } // the new connect has started and is knocking

    assertEquals(-1L, c.getInboundSilenceMs())
  }
}
