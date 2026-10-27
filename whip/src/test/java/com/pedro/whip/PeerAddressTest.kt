package com.pedro.whip

import com.pedro.common.socket.base.UdpPacket
import com.pedro.common.socket.base.UdpStreamSocket
import com.pedro.whip.utils.PeerAddress
import com.pedro.whip.utils.readFrom
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPX R53 — the WHIP media socket accepts datagrams from the chosen remote host only. */
class PeerAddressTest {

  @Test
  fun `the same address matches`() {
    assertTrue(PeerAddress.isSameHost("203.0.113.7", "203.0.113.7"))
    assertTrue(PeerAddress.isSameHost("2001:db8::1", "2001:db8::1"))
  }

  @Test
  fun `a different address is rejected`() {
    assertFalse(PeerAddress.isSameHost("203.0.113.8", "203.0.113.7"))
    assertFalse(PeerAddress.isSameHost("2001:db8::2", "2001:db8::1"))
    assertFalse(PeerAddress.isSameHost("203.0.113.70", "203.0.113.7"))
  }

  @Test
  fun `case, scope id and brackets do not matter`() {
    assertTrue(PeerAddress.isSameHost("2001:DB8::1%wlan0", "2001:db8::1"))
    assertTrue(PeerAddress.isSameHost("[2001:db8::1]", "2001:db8::1"))
  }

  @Test
  fun `an IPv4-mapped IPv6 source matches the IPv4 candidate and not another`() {
    assertTrue(PeerAddress.isSameHost("::ffff:203.0.113.7", "203.0.113.7"))
    assertFalse(PeerAddress.isSameHost("::ffff:203.0.113.8", "203.0.113.7"))
  }

  @Test
  fun `a packet with no source address is rejected`() {
    assertFalse(PeerAddress.isSameHost(null, "203.0.113.7"))
  }

  @Test
  fun `hostile packet hosts are rejected`() {
    assertFalse(PeerAddress.isSameHost("", "203.0.113.7"))
    assertFalse(PeerAddress.isSameHost("203.0.113.7.1", "203.0.113.7"))
    assertFalse(PeerAddress.isSameHost("999.0.113.7", "203.0.113.7"))
    assertFalse(PeerAddress.isSameHost("::ffff:", "203.0.113.7"))
    assertFalse(PeerAddress.isSameHost("example.com", "203.0.113.7"))
  }

  @Test
  fun `a remote that is not an IP literal accepts any source`() {
    assertTrue(PeerAddress.isSameHost("203.0.113.7", "ingest.example.com"))
  }

  @Test
  fun `readFrom skips datagrams from other hosts and returns the first from the peer`() = runTest {
    val socket = ScriptedSocket(
      listOf(
        UdpPacket(byteArrayOf(1, 1, 1), 3, "198.51.100.9", 3478),
        UdpPacket(byteArrayOf(2, 2, 2, 9), 3, "203.0.113.7", 40000),
        UdpPacket(byteArrayOf(3), 1, "203.0.113.7", 40000),
      )
    )
    assertArrayEquals(byteArrayOf(2, 2, 2), socket.readFrom("203.0.113.7"))
    assertEquals(2, socket.reads)
  }

  private class ScriptedSocket(private val packets: List<UdpPacket>) : UdpStreamSocket() {
    var reads = 0
    override suspend fun readPacket(): UdpPacket = packets[reads++]
    override suspend fun connect() {}
    override suspend fun close() {}
    override suspend fun bind() {}
    override suspend fun write(bytes: ByteArray) {}
    override suspend fun write(bytes: ByteArray, host: String, port: Int) {}
    override suspend fun read(): ByteArray = readPacket().data
    override suspend fun setRemoteAddress(host: String, port: Int) {}
    override suspend fun getLocalHost(): String = ""
    override suspend fun getLocalPort(): Int = 0
    override fun isConnected(): Boolean = true
    override fun isReachable(): Boolean = true
  }
}
