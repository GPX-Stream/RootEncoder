package com.pedro.rtsp.rtp

import com.pedro.common.TimeUtils
import com.pedro.common.frame.MediaFrame
import com.pedro.common.socket.base.UdpStreamSocket
import com.pedro.rtsp.rtcp.SenderReportUdp
import com.pedro.rtsp.rtp.packets.BasePacket
import com.pedro.rtsp.rtp.packets.H264Packet
import com.pedro.rtsp.rtp.packets.OpusPacket
import com.pedro.rtsp.rtsp.RtpFrame
import com.pedro.rtsp.utils.RtpTracks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking
import java.nio.ByteBuffer

/**
 * GPX R66 — the 32-bit RTP timestamp across its wrap, about 13.3 h from the origin for video (90 kHz)
 * and 24.9 h for Opus (48 kHz), and at 72 h. WHIP sends these packetizers' output. The sender report
 * has to carry the same low 32 bits as the packet it is built from.
 */
class RtpTimestampWrapTest {

  private val tracks = RtpTracks()

  private fun u32(bytes: ByteArray, at: Int): Long =
    (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (bytes[at + i].toLong() and 0xFF) }

  private suspend fun packet(packetizer: BasePacket, timestampUs: Long): RtpFrame {
    val bytes = byteArrayOf(0, 0, 0, 1, 0x05).plus(ByteArray(20))
    val info = MediaFrame.Info(0, bytes.size, timestampUs, false)
    val frames = mutableListOf<RtpFrame>()
    packetizer.createAndSendPacket(MediaFrame(ByteBuffer.wrap(bytes), info, MediaFrame.Type.VIDEO)) { frames.addAll(it) }
    return frames.single()
  }

  private fun expected(timestampUs: Long, clock: Long): Long =
    ((timestampUs / 1_000_000) * clock + (timestampUs % 1_000_000) * clock / 1_000_000) % (1L shl 32)

  private suspend fun assertWraps(packetizer: BasePacket, clock: Long, frameUs: Long) {
    packetizer.setSSRC(1)
    val wrapUs = (1L shl 32) * 1_000_000 / clock
    val beforeUs = wrapUs - frameUs / 2
    val before = u32(packet(packetizer, beforeUs).buffer, 4)
    val after = u32(packet(packetizer, beforeUs + frameUs).buffer, 4)

    assertTrue("before=$before after=$after", after < before)
    val step = Math.floorMod(after - before, 1L shl 32)
    val ticks = frameUs * clock / 1_000_000
    assertTrue("step=$step ticks=$ticks", step in ticks - 1..ticks + 1)

    val at72h = 72L * 3_600 * 1_000_000
    assertEquals(expected(at72h, clock), u32(packet(packetizer, at72h).buffer, 4))
  }

  @Test
  fun `video RTP timestamps wrap at 32 bits`() = runTest {
    assertWraps(H264Packet(tracks.trackVideo), 90_000, 33_333)
  }

  @Test
  fun `Opus RTP timestamps wrap at 32 bits`() = runTest {
    assertWraps(OpusPacket(tracks.trackAudio), 48_000, 20_000)
  }

  @Test
  fun `a sender report past the wrap carries the packet's own 32 bits`() = runTest {
    Mockito.mockStatic(TimeUtils::class.java).use { clock ->
      clock.`when`<Long>(TimeUtils::getCurrentTimeMillis).then { 10_000L }
      val videoSocket = mock<UdpStreamSocket>()
      val report = SenderReportUdp(tracks, videoSocket, mock())
      report.setSSRC(1, 2)
      val packetizer = H264Packet(tracks.trackVideo).apply { setSSRC(1) }
      val frame = packet(packetizer, 72L * 3_600 * 1_000_000)

      assertTrue(report.update(frame))

      val sent = argumentCaptor<ByteArray>()
      verifyBlocking(videoSocket) { write(sent.capture()) }
      assertTrue("the packetizer's value is past 32 bits", frame.timeStamp > 0xFFFFFFFFL)
      assertEquals(u32(frame.buffer, 4), u32(sent.firstValue, 16))
    }
  }
}
