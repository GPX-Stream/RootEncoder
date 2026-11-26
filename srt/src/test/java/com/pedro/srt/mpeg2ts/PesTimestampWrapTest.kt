package com.pedro.srt.mpeg2ts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * GPX R66 — the 33-bit PES PTS across its wrap, about 26.5 h from the origin, and at 72 h. The PTS is
 * read back from the header bytes: 90 kHz, so a frame 33 333 us later is 3 000 ticks later, modulo 2^33.
 */
class PesTimestampWrapTest {

  /** The five PTS bytes are the last five of the PES header. */
  private fun ptsOf(timestampUs: Long): Long {
    val pes = Pes(256, true, PesType.VIDEO, timestampUs, ByteBuffer.wrap(ByteArray(8)))
    val header = ByteBuffer.allocate(188)
    pes.writeHeader(header)
    val end = header.position()
    val b = LongArray(5) { header.get(end - 5 + it).toLong() and 0xFF }
    return ((b[0] shr 1) and 0x7) shl 30 or
      (b[1] shl 22) or ((b[2] shr 1) shl 15) or
      (b[3] shl 7) or (b[4] shr 1)
  }

  @Test
  fun `the PTS wraps at 33 bits and the step across the wrap is the frame time`() {
    val wrapUs = (1L shl 33) * 100 / 9
    val before = ptsOf(wrapUs - 16_000)
    val after = ptsOf(wrapUs - 16_000 + FRAME_US)

    assertEquals("before $before", true, before in (1L shl 33) - 1_441..(1L shl 33) - 1_439)
    assertEquals(true, after < before)
    // 33 333 us is 2 999.97 ticks, so one tick of rounding either way.
    val step = Math.floorMod(after - before, 1L shl 33)
    assertEquals("step $step", true, step in 2_999..3_000)
  }

  @Test
  fun `at 72 hours the PTS is the 90 kHz count modulo 2 to the 33`() {
    val atUs = 72L * 3_600 * 1_000_000

    assertEquals((atUs * 9 / 100) % (1L shl 33), ptsOf(atUs))
  }

  private companion object {
    const val FRAME_US = 33_333L
  }
}
