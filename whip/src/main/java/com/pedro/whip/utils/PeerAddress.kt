package com.pedro.whip.utils

import com.pedro.common.socket.base.UdpStreamSocket

/**
 * GPX R53 — source check for the WHIP media socket. The socket is bound, not connected (a server may
 * answer from a port other than the one it was sent to), so the OS no longer drops datagrams from
 * other hosts. Anything not from the selected remote candidate's address is dropped here before it
 * reaches STUN, DTLS or the inbound-silence clock (R42). The port is not compared for the reason
 * above. A remote that is not an IP literal cannot be compared as text, so it is accepted.
 */
object PeerAddress {

  /** True when [packetHost] is the same address as [peerHost], ignoring case, scope id and the IPv4-mapped IPv6 form. */
  fun isSameHost(packetHost: String?, peerHost: String): Boolean {
    val expected = normalize(peerHost) ?: return true
    val actual = normalize(packetHost ?: return false) ?: return false
    return actual == expected
  }

  /** Null when [host] is not an IP literal. */
  private fun normalize(host: String): String? {
    var value = host.trim().substringBefore('%').lowercase()
    if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length - 1)
    if (value.startsWith("::ffff:") && value.substringAfterLast(':').let { isIpv4(it) }) {
      value = value.substringAfterLast(':')
    }
    return if (isIpv4(value) || isIpv6(value)) value else null
  }

  private fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toInt() <= 255 }
  }

  private fun isIpv6(value: String): Boolean =
    value.contains(':') && value.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }
}

/**
 * GPX R53 — reads until a datagram from [peerHost] arrives and returns its bytes. Datagrams from any
 * other host are dropped. Suspends like [UdpStreamSocket.read], so a caller's timeout still applies.
 */
suspend fun UdpStreamSocket.readFrom(peerHost: String): ByteArray {
  while (true) {
    val packet = readPacket()
    if (PeerAddress.isSameHost(packet.host, peerHost)) return packet.data.sliceArray(0 until packet.size)
  }
}
