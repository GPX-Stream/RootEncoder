package com.pedro.library.util

import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.common.frame.MediaFrame
import com.pedro.library.base.recording.RecordController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/**
 * GPX R59 — a recording file begins on a keyframe that is preceded by the codec headers.
 *
 * The controller is driven through [Mpeg2TsMuxerRecordController.onWriteFrame] directly, one frame
 * at a time, so the result does not depend on the muxer coroutine's timing. The output is the real
 * MPEG-TS file; its video PES payload is read back and the H.264 NAL unit types are listed.
 */
class Mpeg2TsMuxerRecordControllerTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val startCode = byteArrayOf(0, 0, 0, 1)
  private val sps = startCode + byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E, 0x8C.toByte(), 0x8D.toByte())
  private val pps = startCode + byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
  private val idrSlice = startCode + byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x33, 0x11)
  private val pSlice = startCode + byteArrayOf(0x41, 0x9A.toByte(), 0x24, 0x6C, 0x01)

  private fun frame(data: ByteArray, key: Boolean, timestampUs: Long) = MediaFrame(
    ByteBuffer.wrap(data),
    MediaFrame.Info(0, data.size, timestampUs, key),
    MediaFrame.Type.VIDEO
  )

  /** NAL unit types of the video elementary stream in [file], in file order, without the AUD. */
  private fun videoNalTypes(file: File, h265: Boolean = false): List<Int> {
    val bytes = file.readBytes()
    val tsPacket = 188
    // the video PES is the one whose payload opens with 00 00 01 E0
    var videoPid = -1
    var i = 0
    while (i + tsPacket <= bytes.size) {
      val pid = ((bytes[i + 1].toInt() and 0x1F) shl 8) or (bytes[i + 2].toInt() and 0xFF)
      val start = (bytes[i + 1].toInt() and 0x40) != 0
      val payloadAt = payloadOffset(bytes, i)
      if (start && payloadAt + 4 <= i + tsPacket &&
        bytes[payloadAt].toInt() == 0 && bytes[payloadAt + 1].toInt() == 0 &&
        bytes[payloadAt + 2].toInt() == 1 && (bytes[payloadAt + 3].toInt() and 0xFF) == 0xE0
      ) {
        videoPid = pid
        break
      }
      i += tsPacket
    }
    if (videoPid < 0) return emptyList()

    val es = java.io.ByteArrayOutputStream()
    i = 0
    while (i + tsPacket <= bytes.size) {
      val pid = ((bytes[i + 1].toInt() and 0x1F) shl 8) or (bytes[i + 2].toInt() and 0xFF)
      if (pid == videoPid) {
        var at = payloadOffset(bytes, i)
        if ((bytes[i + 1].toInt() and 0x40) != 0) {
          // skip the PES header: 6 bytes + 3 flag bytes + header data length
          at += 9 + (bytes[at + 8].toInt() and 0xFF)
        }
        es.write(bytes, at, i + tsPacket - at)
      }
      i += tsPacket
    }
    val es2 = es.toByteArray()
    val types = mutableListOf<Int>()
    var p = 0
    while (p + 4 < es2.size) {
      if (es2[p].toInt() == 0 && es2[p + 1].toInt() == 0 && es2[p + 2].toInt() == 1) {
        types.add(if (h265) (es2[p + 3].toInt() shr 1) and 0x3F else es2[p + 3].toInt() and 0x1F)
        p += 3
      } else {
        p++
      }
    }
    // access unit delimiters: 9 in H.264, 35 in H.265
    return types.filter { it != if (h265) 35 else 9 }
  }

  private fun payloadOffset(bytes: ByteArray, packetStart: Int): Int {
    val control = (bytes[packetStart + 3].toInt() shr 4) and 0x3
    var at = packetStart + 4
    if (control == 2 || control == 3) at += 1 + (bytes[at].toInt() and 0xFF)
    return at
  }

  private fun startedController(h265: Boolean = false): Pair<Mpeg2TsMuxerRecordController, File> {
    val file = folder.newFile("out.ts")
    val controller = Mpeg2TsMuxerRecordController()
    if (h265) controller.updateInfo(VideoCodec.H265, AudioCodec.AAC)
    controller.startRecord(file.absolutePath, null, RecordController.RecordTracks.VIDEO)
    return controller to file
  }

  @Test
  fun `GIVEN a file started mid-GOP WHEN a keyframe arrives THEN the file begins with the headers and that keyframe`() {
    val (controller, file) = startedController()

    runBlocking {
      // the file opens in the middle of a GOP: delta frames first
      controller.onWriteFrame(frame(pSlice, key = false, timestampUs = 0))
      controller.onWriteFrame(frame(pSlice, key = false, timestampUs = 33_000))
      // the keyframe is the first frame to carry the codec headers inline
      controller.onWriteFrame(frame(sps + pps + idrSlice, key = true, timestampUs = 66_000))
      controller.onWriteFrame(frame(pSlice, key = false, timestampUs = 100_000))
    }
    controller.stopRecord()

    val types = videoNalTypes(file)
    // SPS, PPS, IDR, then the delta frame that followed
    assertEquals(listOf(7, 8, 5, 1), types)
  }

  @Test
  fun `GIVEN a file started mid-GOP WHEN the keyframe is written THEN the status moves to RECORDING at the keyframe`() {
    val (controller, _) = startedController()
    val statuses = mutableListOf<RecordController.Status>()
    statuses.add(controller.getStatus())

    runBlocking {
      controller.onWriteFrame(frame(pSlice, key = false, timestampUs = 0))
      statuses.add(controller.getStatus())
      controller.onWriteFrame(frame(sps + pps + idrSlice, key = true, timestampUs = 66_000))
      statuses.add(controller.getStatus())
    }
    controller.stopRecord()

    assertEquals(
      listOf(
        RecordController.Status.STARTED,
        RecordController.Status.STARTED,
        RecordController.Status.RECORDING
      ),
      statuses
    )
  }

  @Test
  fun `GIVEN a keyframe is the first frame WHEN written THEN it is written exactly once`() {
    val (controller, file) = startedController()

    runBlocking {
      controller.onWriteFrame(frame(sps + pps + idrSlice, key = true, timestampUs = 0))
      controller.onWriteFrame(frame(pSlice, key = false, timestampUs = 33_000))
    }
    controller.stopRecord()

    val types = videoNalTypes(file)
    assertEquals(1, types.count { it == 5 })
    assertTrue(types.first() == 7)
  }

  // H.265 parameter sets and one slice, in the shape a MediaCodec keyframe buffer has
  private val vps = startCode + byteArrayOf(0x40, 0x01, 0x0C, 0x01, 0xFF.toByte())
  private val sps265 = startCode + byteArrayOf(0x42, 0x01, 0x01, 0x01, 0x60, 0x00)
  private val pps265 = startCode + byteArrayOf(0x44, 0x01, 0xC1.toByte(), 0x72)
  private val idr265 = startCode + byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x08, 0x40)
  private val delta265 = startCode + byteArrayOf(0x02, 0x01, 0xD0.toByte(), 0x09)

  @Test
  fun `GIVEN an H265 file started mid-GOP WHEN keyframes arrive THEN each keyframe carries the VPS, SPS and PPS once and in order`() {
    val (controller, file) = startedController(h265 = true)

    runBlocking {
      controller.onWriteFrame(frame(delta265, key = false, timestampUs = 0))
      controller.onWriteFrame(frame(vps + sps265 + pps265 + idr265, key = true, timestampUs = 33_000))
      controller.onWriteFrame(frame(delta265, key = false, timestampUs = 66_000))
      controller.onWriteFrame(frame(vps + sps265 + pps265 + idr265, key = true, timestampUs = 100_000))
    }
    controller.stopRecord()

    // VPS 32, SPS 33, PPS 34, IDR_W_RADL 19, delta 1; the first keyframe, one delta, the next keyframe
    assertEquals(listOf(32, 33, 34, 19, 1, 32, 33, 34, 19), videoNalTypes(file, h265 = true))
  }
}
