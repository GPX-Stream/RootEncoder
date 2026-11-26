package com.pedro.encoder.audio

import android.media.MediaCodec
import android.media.MediaFormat
import com.pedro.encoder.Frame
import com.pedro.library.base.StreamClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * GPX R66 — the stream timestamp across two starts with no recording running.
 *
 * In this package for [AudioEncoder.calculatePts], so the timestamps compared are the encoder's own
 * arithmetic on the value [StreamClock] hands it. That startStream hands the encoders that value is
 * StreamBaseTimestampOriginTest's. The video encoder's own rebase is not run by either.
 */
class StreamClockContinuityTest {

  private fun newEncoder() = AudioEncoder(object : GetAudioData {
    override fun getAudioData(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {}
    override fun onAudioFormat(mediaFormat: MediaFormat) {}
  })

  /** One run of an encoder: started at [startedAtUs], stamping a frame at each of [frameTimesUs]. */
  private fun run(clock: StreamClock, encoder: AudioEncoder, startedAtUs: Long, vararg frameTimesUs: Long): List<Long> {
    val startTs = clock.startTimestampUs(startedAtUs)
    return frameTimesUs.map { encoder.calculatePts(Frame(ByteArray(4), 0, 4, it), startTs) }
  }

  @Test
  fun `with an origin, a second start is later than the end of the first`() {
    val clock = StreamClock().apply { setOrigin(ORIGIN_US, ORIGIN_US) }
    val encoder = newEncoder()

    val first = run(clock, encoder, ORIGIN_US + 5 * SECOND, ORIGIN_US + 5 * SECOND, ORIGIN_US + 65 * SECOND)
    val second = run(clock, encoder, ORIGIN_US + 70 * SECOND, ORIGIN_US + 70 * SECOND)

    assertTrue("first=$first second=$second", second.first() > first.last())
    assertEquals(70 * SECOND, second.first())
  }

  @Test
  fun `a second engine given the same origin carries on from the first`() {
    val firstClock = StreamClock().apply { setOrigin(ORIGIN_US, ORIGIN_US + SECOND) }
    val first = run(firstClock, newEncoder(), ORIGIN_US + SECOND, ORIGIN_US + SECOND, ORIGIN_US + 30 * SECOND)

    val rebuilt = StreamClock().apply { setOrigin(ORIGIN_US, ORIGIN_US + 40 * SECOND) }
    val second = run(rebuilt, newEncoder(), ORIGIN_US + 40 * SECOND, ORIGIN_US + 40 * SECOND)

    assertTrue("first=$first second=$second", second.first() > first.last())
  }

  @Test
  fun `with no origin each start counts from itself`() {
    val clock = StreamClock()

    assertEquals(ORIGIN_US, clock.startTimestampUs(ORIGIN_US))
    assertEquals(ORIGIN_US + SECOND, clock.startTimestampUs(ORIGIN_US + SECOND))
  }

  @Test
  fun `zero clears the origin`() {
    val clock = StreamClock().apply { setOrigin(ORIGIN_US, ORIGIN_US) }

    clock.setOrigin(0, ORIGIN_US + SECOND)

    assertEquals(ORIGIN_US + SECOND, clock.startTimestampUs(ORIGIN_US + SECOND))
  }

  @Test
  fun `a negative origin or one ahead of the clock is refused and the held one stays`() {
    val clock = StreamClock().apply { setOrigin(ORIGIN_US, ORIGIN_US) }

    assertThrows(IllegalArgumentException::class.java) { clock.setOrigin(-1, ORIGIN_US) }
    // An origin ahead of the clock would clamp every timestamp to zero.
    assertThrows(IllegalArgumentException::class.java) { clock.setOrigin(ORIGIN_US + SECOND, ORIGIN_US) }

    assertEquals(ORIGIN_US, clock.startTimestampUs(ORIGIN_US + 2 * SECOND))
  }

  private companion object {
    const val SECOND = 1_000_000L
    const val ORIGIN_US = 3_600 * SECOND
  }
}
