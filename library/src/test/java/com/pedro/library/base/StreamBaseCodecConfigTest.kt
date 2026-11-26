package com.pedro.library.base

import android.media.MediaCodec
import com.pedro.encoder.video.GetVideoData
import com.pedro.library.base.recording.RecordController
import com.pedro.library.util.FpsListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import java.nio.ByteBuffer

/**
 * GPX R66 — what StreamBase does with a video encoder's output: a codec-config buffer reaches neither
 * the sender nor the record controller; a picture reaches both.
 *
 * The two GetVideoData callbacks are StreamBase's own, built here on a Mockito instance that never ran
 * the constructor (see StreamBaseTimestampOriginTest), with the fields they read set by name.
 */
class StreamBaseCodecConfigTest {

  private val recorder = mock<RecordController>()

  private fun newStream(ownRecordEncoder: Boolean): StreamBase {
    val stream = Mockito.mock(StreamBase::class.java, Mockito.CALLS_REAL_METHODS)
    stream.set("recordController", recorder)
    stream.set("fpsListener", FpsListener())
    stream.set("differentRecordResolution", ownRecordEncoder)
    return stream
  }

  private fun StreamBase.set(name: String, value: Any) {
    val field = StreamBase::class.java.getDeclaredField(name)
    field.isAccessible = true
    field.set(this, value)
  }

  /** The callback StreamBase hands the stream or the record video encoder. */
  private fun StreamBase.callback(name: String): GetVideoData {
    val type = Class.forName("com.pedro.library.base.StreamBase\$$name\$1")
    val constructor = type.getDeclaredConstructor(StreamBase::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(this) as GetVideoData
  }

  private fun info(flags: Int) = MediaCodec.BufferInfo().also {
    it.flags = flags
    it.size = 8
  }

  private fun sentTo(stream: StreamBase): Int =
    Mockito.mockingDetails(stream).invocations.count { it.method.name == "getVideoDataImp" }

  private fun recorded(): Int =
    Mockito.mockingDetails(recorder).invocations.count { it.method.name == "recordVideo" }

  private fun deliver(callback: GetVideoData, flags: Int) =
    callback.getVideoData(ByteBuffer.allocate(8), info(flags))

  @Test
  fun `a config buffer from the stream encoder reaches neither the sender nor a recording that taps it`() {
    val stream = newStream(ownRecordEncoder = false)

    deliver(stream.callback("getVideoData"), MediaCodec.BUFFER_FLAG_CODEC_CONFIG)

    assertEquals("sender", 0, sentTo(stream))
    assertEquals("recorder", 0, recorded())
  }

  @Test
  fun `a config buffer from the record encoder does not reach the recording`() {
    val stream = newStream(ownRecordEncoder = true)

    deliver(stream.callback("getVideoDataRecord"), MediaCodec.BUFFER_FLAG_CODEC_CONFIG)

    assertEquals(0, recorded())
  }

  @Test
  fun `a picture reaches the sender and the recording`() {
    val stream = newStream(ownRecordEncoder = false)
    val callback = stream.callback("getVideoData")

    deliver(callback, 0)
    deliver(callback, MediaCodec.BUFFER_FLAG_KEY_FRAME)
    // A keyframe that carries the config flag too is still a picture.
    deliver(callback, MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_CODEC_CONFIG)

    assertEquals("sender", 3, sentTo(stream))
    assertEquals("recorder", 3, recorded())
  }

  @Test
  fun `a picture from the record encoder reaches the recording`() {
    val stream = newStream(ownRecordEncoder = true)

    deliver(stream.callback("getVideoDataRecord"), MediaCodec.BUFFER_FLAG_KEY_FRAME)

    assertEquals(1, recorded())
  }
}
