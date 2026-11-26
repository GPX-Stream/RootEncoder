package com.pedro.encoder.input.audio

import android.media.AudioRecord
import android.os.SystemClock
import com.pedro.encoder.Frame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** GPX R66 — a muted microphone goes on handing the encoder frames stamped on the device clock. */
class MutedFrameStreamClockTest {

  @Test
  fun `muting and unmuting leaves every frame stamped on the clock`() {
    var nowMs = 0L
    Mockito.mockStatic(SystemClock::class.java).use { clock ->
      clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { nowMs }
      val record = mock<AudioRecord>()
      whenever(record.read(any<ByteArray>(), any(), any())).thenReturn(64)
      val microphone = MicrophoneManager(object : GetMicrophoneData {
        override fun inputPCMData(frame: Frame) {}
      })
      val field = MicrophoneManager::class.java.getDeclaredField("audioRecord")
      field.isAccessible = true
      field.set(microphone, record)

      fun stampAt(ms: Long): Long {
        nowMs = ms
        return microphone.read().timeStamp
      }

      assertEquals(1_000_000L, stampAt(1_000))
      microphone.mute()
      assertEquals(2_000_000L, stampAt(2_000))
      microphone.unMute()
      assertEquals(3_000_000L, stampAt(3_000))
    }
  }
}
