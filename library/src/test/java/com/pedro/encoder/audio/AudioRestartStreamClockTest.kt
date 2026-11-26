package com.pedro.encoder.audio

import android.media.MediaCodec
import android.media.MediaFormat
import com.pedro.encoder.BaseEncoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * GPX R66 — the audio encoder's start timestamp across the restart its crash recovery does: stop(false),
 * then restart(), whose first step is start(false). restart() itself needs a real MediaCodec.
 */
class AudioRestartStreamClockTest {

  @Test
  fun `a restart that keeps the baseline keeps the start timestamp`() {
    val encoder = AudioEncoder(object : GetAudioData {
      override fun getAudioData(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {}
      override fun onAudioFormat(mediaFormat: MediaFormat) {}
    })
    val field = BaseEncoder::class.java.getDeclaredField("presentTimeUs")
    field.isAccessible = true
    field.setLong(encoder, ORIGIN_US)

    encoder.stop(false)
    encoder.start(false)

    assertEquals(ORIGIN_US, field.getLong(encoder))
  }

  private companion object {
    const val ORIGIN_US = 3_600_000_000L
  }
}
