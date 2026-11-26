package com.pedro.encoder.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import com.pedro.encoder.BaseEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import java.nio.ByteBuffer

/**
 * GPX R66 — the video encoder's own output path, [VideoEncoder.checkBuffer], with Surface input.
 *
 * In this package for that method, and in `library` because Mockito is a test dependency there. The
 * start timestamp is written into the encoder directly: start() needs a real MediaCodec.
 */
class VideoEncoderTimestampOriginTest {

  private var nowMs = 0L
  private lateinit var systemClock: MockedStatic<SystemClock>
  private var parameterSetReports = 0

  private val encoder = VideoEncoder(object : GetVideoData {
    override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
      parameterSetReports++
    }
    override fun getVideoData(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {}
    override fun onVideoFormat(mediaFormat: MediaFormat) {}
  })

  @Before
  fun setUp() {
    // TimeUtils reads this on the JVM, where Build.VERSION.SDK_INT is 0.
    systemClock = Mockito.mockStatic(SystemClock::class.java)
    systemClock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { nowMs }
    set(VideoEncoder::class.java, "formatVideoEncoder", FormatVideoEncoder.SURFACE)
    set(VideoEncoder::class.java, "spsPpsSetted", true)
  }

  @After
  fun tearDown() {
    systemClock.close()
  }

  private fun set(owner: Class<*>, name: String, value: Any) {
    val field = owner.getDeclaredField(name)
    field.isAccessible = true
    field.set(encoder, value)
  }

  /** The state a start leaves: counting from [startTsUs], which is the origin when one is held. */
  private fun startedFrom(startTsUs: Long, originHeld: Boolean) {
    encoder.setTimestampOriginHeld(originHeld)
    set(BaseEncoder::class.java, "presentTimeUs", startTsUs)
  }

  private class Output(val forwarded: Boolean, val timestampUs: Long)

  /** One buffer stamped [stampUs] by its surface, coming out of the codec at [atUs]. */
  private fun output(stampUs: Long, atUs: Long, flags: Int = 0, bytes: ByteArray = FRAME): Output {
    nowMs = atUs / 1_000
    val info = MediaCodec.BufferInfo()
    info.presentationTimeUs = stampUs
    info.flags = flags
    info.size = bytes.size
    val forwarded = encoder.checkBuffer(ByteBuffer.wrap(bytes), info)
    return Output(forwarded, info.presentationTimeUs)
  }

  @Test
  fun `with an origin, a first output more than a second late is still counted from the origin`() {
    startedFrom(ORIGIN_US, originHeld = true)
    val stamp = ORIGIN_US + HOUR

    val first = output(stampUs = stamp, atUs = stamp + 1_500_000)
    val next = output(stampUs = stamp + 33_000, atUs = stamp + 1_510_000)

    assertTrue(first.forwarded)
    assertEquals(HOUR, first.timestampUs)
    assertEquals(HOUR + 33_000, next.timestampUs)
  }

  @Test
  fun `with an origin, a stamp from another clock is counted from its first frame`() {
    startedFrom(ORIGIN_US, originHeld = true)

    // A media timestamp near zero, as a decoder-fed surface gives.
    val first = output(stampUs = 5_000_000, atUs = ORIGIN_US + HOUR)
    val next = output(stampUs = 5_033_000, atUs = ORIGIN_US + HOUR + 33_000)

    assertEquals(0, first.timestampUs)
    assertEquals(33_000, next.timestampUs)
  }

  @Test
  fun `with an origin, a stamp well ahead of the clock is not taken for the shared clock`() {
    startedFrom(ORIGIN_US, originHeld = true)
    val now = ORIGIN_US + HOUR

    assertEquals(0, output(stampUs = now + 5_000_000, atUs = now).timestampUs)
  }

  @Test
  fun `with no origin, an on-time first output is counted from the start`() {
    val startedAt = ORIGIN_US
    startedFrom(startedAt, originHeld = false)

    assertEquals(40_000, output(stampUs = startedAt + 40_000, atUs = startedAt + 60_000).timestampUs)
  }

  @Test
  fun `with no origin, a late first output is counted from its own frame, as before`() {
    startedFrom(ORIGIN_US, originHeld = false)

    assertEquals(0, output(stampUs = ORIGIN_US + 40_000, atUs = ORIGIN_US + 1_540_000).timestampUs)
  }

  @Test
  fun `with an origin, the codec config buffer is not sent on, and the frame after it is counted from the origin`() {
    startedFrom(ORIGIN_US, originHeld = true)
    val now = ORIGIN_US + HOUR

    val config = output(stampUs = 0, atUs = now, flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG, bytes = CONFIG)
    val frame = output(stampUs = now, atUs = now + 20_000)

    assertFalse(config.forwarded)
    assertTrue(frame.forwarded)
    assertEquals(HOUR, frame.timestampUs)
  }

  @Test
  fun `with an origin, the parameter sets are still read from a config buffer`() {
    startedFrom(ORIGIN_US, originHeld = true)
    set(VideoEncoder::class.java, "spsPpsSetted", false)
    // H265: the H264 extraction answers in an android.util.Pair, which holds nothing on the plain JVM.
    encoder.type = com.pedro.common.VideoCodec.H265

    val config = output(stampUs = 0, atUs = ORIGIN_US + HOUR, flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG, bytes = CONFIG_H265)

    assertFalse(config.forwarded)
    assertEquals(1, parameterSetReports)
  }

  @Test
  fun `with an origin, a keyframe that also carries the config flag is sent on`() {
    startedFrom(ORIGIN_US, originHeld = true)
    val now = ORIGIN_US + HOUR

    val flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_KEY_FRAME

    assertTrue(output(stampUs = now, atUs = now, flags = flags).forwarded)
  }

  @Test
  fun `with no origin, the codec config buffer is sent on, as before`() {
    startedFrom(ORIGIN_US, originHeld = false)

    assertTrue(output(stampUs = 0, atUs = ORIGIN_US, flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG, bytes = CONFIG).forwarded)
  }

  private companion object {
    const val ORIGIN_US = 3_600_000_000L
    const val HOUR = 3_600_000_000L
    val FRAME = byteArrayOf(0, 0, 0, 1, 0x41, 1, 2, 3)
    val CONFIG = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F, 0, 0, 0, 1, 0x68, 0x01, 0x02)
    // VPS, SPS, PPS.
    val CONFIG_H265 = byteArrayOf(
      0, 0, 0, 1, 0x40, 0x01, 0x0C, 0, 0, 0, 1, 0x42, 0x01, 0x01, 0, 0, 0, 1, 0x44, 0x01, 0x0F,
    )
  }
}
