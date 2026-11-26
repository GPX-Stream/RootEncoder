package com.pedro.library.base

import android.os.SystemClock
import com.pedro.encoder.audio.AudioEncoder
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.encoder.video.VideoEncoder
import com.pedro.library.base.recording.RecordController
import com.pedro.library.view.GlStreamInterface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * GPX R66 — the real startStream and stopStream, with no recording, hand the encoders the origin.
 *
 * StreamBase's constructor cannot run on the plain JVM (see TransportConstructionTest), so the object
 * under test is a mock that calls the real methods and is built without the constructor. Every field the
 * start and stop paths read is therefore set here by name; a new one fails this test with a
 * NullPointerException until it is added.
 */
class StreamBaseTimestampOriginTest {

  private var nowMs = 0L
  private lateinit var systemClock: MockedStatic<SystemClock>

  private val video = mock<VideoEncoder>()
  private val recordVideo = mock<VideoEncoder>()
  private val audio = mock<AudioEncoder>()

  @Before
  fun setUp() {
    // TimeUtils reads this on the JVM, where Build.VERSION.SDK_INT is 0.
    systemClock = Mockito.mockStatic(SystemClock::class.java)
    systemClock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { nowMs }
  }

  @After
  fun tearDown() {
    systemClock.close()
  }

  private fun newStream(): StreamBase {
    val stream = Mockito.mock(StreamBase::class.java, Mockito.CALLS_REAL_METHODS)
    val videoSource = mock<VideoSource>()
    whenever(videoSource.isRunning()).thenReturn(true)
    val surface = mock<android.view.Surface>()
    whenever(video.inputSurface).thenReturn(surface)
    whenever(recordVideo.inputSurface).thenReturn(surface)
    stream.set("videoEncoder\$delegate", lazyOf(video))
    stream.set("videoEncoderRecord\$delegate", lazyOf(recordVideo))
    stream.set("audioEncoder\$delegate", lazyOf(audio))
    stream.set("glInterface", mock<GlStreamInterface>())
    stream.set("videoSource", videoSource)
    stream.set("audioSource", mock<AudioSource>())
    stream.set("recordController", mock<RecordController>())
    stream.set("streamClock", StreamClock())
    stream.set("lifecycleLock", Any())
    stream.set("videoSourceStartLock", Any())
    // A record encoder of its own, so all three encoders are started.
    stream.set("differentRecordResolution", true)
    return stream
  }

  private fun StreamBase.set(name: String, value: Any) {
    val field = StreamBase::class.java.getDeclaredField(name)
    field.isAccessible = true
    field.set(this, value)
  }

  private fun startAndStop(stream: StreamBase, atMs: Long) {
    nowMs = atMs
    stream.startStream("srt://example.invalid:1")
    stream.stopStream()
  }

  private fun startsOf(encoder: com.pedro.encoder.BaseEncoder): List<Long> {
    val captor = ArgumentCaptor.forClass(Long::class.javaObjectType)
    Mockito.verify(encoder, Mockito.atLeastOnce()).start(captor.capture() ?: 0L)
    return captor.allValues
  }

  @Test
  fun `with an origin, every start gives all three encoders the origin`() {
    val stream = newStream()
    nowMs = ORIGIN_MS
    stream.setTimestampOrigin(ORIGIN_MS * 1_000)

    startAndStop(stream, ORIGIN_MS + 5_000)
    startAndStop(stream, ORIGIN_MS + 90_000)

    val expected = listOf(ORIGIN_MS * 1_000, ORIGIN_MS * 1_000)
    assertEquals(expected, startsOf(video))
    assertEquals(expected, startsOf(recordVideo))
    assertEquals(expected, startsOf(audio))
  }

  @Test
  fun `both video encoders are told whether an origin is held`() {
    val stream = newStream()
    nowMs = ORIGIN_MS

    stream.setTimestampOrigin(ORIGIN_MS * 1_000)
    stream.setTimestampOrigin(0)

    listOf(video, recordVideo).forEach { encoder ->
      val order = Mockito.inOrder(encoder)
      order.verify(encoder).setTimestampOriginHeld(true)
      order.verify(encoder).setTimestampOriginHeld(false)
    }
  }

  @Test
  fun `with no origin, each start gives the encoders its own time`() {
    val stream = newStream()

    startAndStop(stream, ORIGIN_MS + 5_000)
    startAndStop(stream, ORIGIN_MS + 90_000)

    val expected = listOf((ORIGIN_MS + 5_000) * 1_000, (ORIGIN_MS + 90_000) * 1_000)
    assertEquals(expected, startsOf(video))
    assertEquals(expected, startsOf(recordVideo))
    assertEquals(expected, startsOf(audio))
  }

  private companion object {
    const val ORIGIN_MS = 3_600_000L
  }
}
