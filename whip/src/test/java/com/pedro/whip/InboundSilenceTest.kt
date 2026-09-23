/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.whip

import com.pedro.common.ConnectChecker
import com.pedro.common.socket.base.UdpPacket
import com.pedro.common.socket.base.UdpStreamSocket
import com.pedro.whip.dtls.DtlsTransport
import com.pedro.whip.utils.InboundSilenceTracker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * GPX R42 — the WHIP inbound-silence signal: the tracker's own arithmetic, and which inbound
 * packets [WhipClient.handleMessages] counts as the server's media-plane feedback.
 */
class InboundSilenceTest {

  private class FakeClock(var now: Long = 1_000L) : () -> Long {
    override fun invoke(): Long = now
  }

  /** Returns [packet] from every read; nothing else is exercised by handleMessages' counting path. */
  private class FakeSocket(private val packet: ByteArray) : UdpStreamSocket() {
    override suspend fun bind() {}
    override suspend fun write(bytes: ByteArray) {}
    override suspend fun write(bytes: ByteArray, host: String, port: Int) {}
    override suspend fun read(): ByteArray = packet
    override suspend fun readPacket(): UdpPacket = throw UnsupportedOperationException()
    override suspend fun setRemoteAddress(host: String, port: Int) {}
    override suspend fun getLocalHost(): String = ""
    override suspend fun getLocalPort(): Int = 0
    override suspend fun connect() {}
    override suspend fun close() {}
    override fun isConnected(): Boolean = true
    override fun isReachable(): Boolean = true
  }

  @Test
  fun `tracker reads unknown before the session starts`() {
    assertEquals(-1L, InboundSilenceTracker(FakeClock()).silenceMs())
  }

  @Test
  fun `tracker counts silence from session start until the first inbound packet`() {
    val clock = FakeClock()
    val tracker = InboundSilenceTracker(clock)
    tracker.start()
    clock.now += 4_000
    assertEquals(4_000L, tracker.silenceMs())
  }

  @Test
  fun `an inbound packet restarts the silence clock`() {
    val clock = FakeClock()
    val tracker = InboundSilenceTracker(clock)
    tracker.start()
    clock.now += 9_000
    tracker.onInbound()
    clock.now += 250
    assertEquals(250L, tracker.silenceMs())
  }

  @Test
  fun `reset returns to unknown so a retry cannot inherit the old session's silence`() {
    val clock = FakeClock()
    val tracker = InboundSilenceTracker(clock)
    tracker.start()
    clock.now += 20_000
    tracker.reset()
    assertEquals(-1L, tracker.silenceMs())
  }

  @Test
  fun `a not-streaming client reports unknown`() {
    assertEquals(-1L, WhipClient(mock<ConnectChecker>()).getInboundSilenceMs())
  }

  @Test
  fun `an RTP or RTCP packet from the server stamps the tracker`() = runTest {
    val client = WhipClient(mock<ConnectChecker>())
    // First byte 0x80: RTP/RTCP version 2, the 128..191 range RFC 7983 assigns to the media plane.
    val socket = FakeSocket(byteArrayOf(0x80.toByte(), 0xC9.toByte(), 0x00, 0x01))
    client.handleMessages(socket, "127.0.0.1", 9, DtlsTransport(socket))
    assertNotEquals(-1L, client.inboundSilence.silenceMs())
  }

  @Test
  fun `STUN and DTLS packets do not count as media-plane feedback`() = runTest {
    val client = WhipClient(mock<ConnectChecker>())
    // 0x00: a STUN message (ICE only proves the session is held). 0x16: a DTLS handshake record.
    for (first in listOf(0x00, 0x16)) {
      val socket = FakeSocket(byteArrayOf(first.toByte(), 0x01, 0x00, 0x00))
      client.handleMessages(socket, "127.0.0.1", 9, DtlsTransport(socket))
    }
    assertEquals(-1L, client.inboundSilence.silenceMs())
  }
}
