package com.pedro.rtmp.rtmp.message

import com.pedro.rtmp.FakeRtmpSocket
import com.pedro.rtmp.flv.FlvPacket
import com.pedro.rtmp.flv.FlvType
import com.pedro.rtmp.utils.CommandSessionHistory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GPX R66 — media messages at timestamps a long-lived origin reaches: either side of the extended
 * timestamp field (0xffffff ms, about 4.66 h) and 72 h. Each is written in several chunks, so the
 * continuation headers carry it too, and read back with this module's own reader. That reader is not
 * an ingest: it shows the two sides of this code agree.
 */
class LargeTimestampTest {

  private val body = ByteArray(300) { it.toByte() }

  @Test
  fun `video and audio keep a large timestamp across every chunk`() = runTest {
    val timestamps = listOf(0xfffffeL, 0xffffffL, 0x1000000L, SEVENTY_TWO_HOURS_MS)
    for (timestamp in timestamps) {
      for (type in listOf(FlvType.VIDEO, FlvType.AUDIO)) {
        val packet = FlvPacket(body.clone(), timestamp, body.size, type)
        val message = if (type == FlvType.VIDEO) Video(packet) else Audio(packet)
        val writer = FakeRtmpSocket()
        message.writeHeader(writer)
        message.writeBody(writer, CHUNK_SIZE)
        val written = writer.output.toByteArray()

        // Two continuation headers, each one byte, plus four more when the timestamp is extended.
        val extended = if (timestamp >= 0xffffff) 4 else 0
        assertEquals("size at $timestamp $type", body.size + 12 + extended + 2 * (1 + extended), written.size)

        // The module's reader: it takes the timestamp and consumes every chunk. It keeps no media body.
        val reader = FakeRtmpSocket().apply { setInputBytes(written) }
        val read = RtmpMessage.getRtmpMessage(reader, CHUNK_SIZE, CommandSessionHistory())
        assertEquals("timestamp at $timestamp $type", timestamp.toInt(), read.header.timeStamp)
        assertEquals("nothing left unread at $timestamp $type", -1, reader.read())

        // The bytes themselves: each continuation header repeats the extended field, and what is left
        // between the headers is the body.
        val field = ByteArray(extended) { i -> (timestamp shr (8 * (3 - i))).toByte() }
        assertArrayEquals("first header at $timestamp", field, written.copyOfRange(12, 12 + extended))
        var at = 12 + extended
        var rest = byteArrayOf()
        while (rest.size < body.size) {
          if (rest.isNotEmpty()) {
            assertArrayEquals("continuation at $timestamp", field, written.copyOfRange(at + 1, at + 1 + extended))
            at += 1 + extended
          }
          val size = minOf(CHUNK_SIZE, body.size - rest.size)
          rest += written.copyOfRange(at, at + size)
          at += size
        }
        assertArrayEquals("body at $timestamp $type", body, rest)
      }
    }
  }

  private companion object {
    const val CHUNK_SIZE = 128
    const val SEVENTY_TWO_HOURS_MS = 259_200_000L
  }
}
