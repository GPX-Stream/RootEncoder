package com.pedro.common.socket

import com.pedro.common.socket.java.UdpStreamSocketJava
import com.pedro.common.socket.base.UdpStreamSocket
import com.pedro.common.socket.ktor.UdpStreamSocketKtor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * GPX R68 — a received datagram reports its sender as a numeric address. The WHIP source filter (R53)
 * compares that text with the remote candidate's IP, so a host name there drops the datagram. The
 * difference only shows for an address that resolves to a name, so the tests run on a local IPv4
 * address that does, and are skipped on a machine that has none.
 */
class UdpStreamSocketSenderHostTest {

  @Test
  fun `GIVEN a ktor udp socket WHEN a datagram arrives THEN the sender host is numeric`() {
    assertSenderHostIsNumeric { host, port -> UdpStreamSocketKtor(host, port) }
  }

  @Test
  fun `GIVEN a java udp socket WHEN a datagram arrives THEN the sender host is numeric`() {
    assertSenderHostIsNumeric { host, port -> UdpStreamSocketJava(host, port) }
  }

  private fun assertSenderHostIsNumeric(create: (String, Int) -> UdpStreamSocket) = runBlocking {
    val local = namedLocalAddress()
    assumeTrue("no local IPv4 address resolves to a name", local != null)
    val address = local!!
    val ip = address.hostAddress!!
    // bind() takes the port it is given and does not report an ephemeral one, so pick a free port first
    val port = DatagramSocket(0, address).use { it.localPort }
    val socket = create(ip, port)
    socket.bind()
    try {
      DatagramSocket(0, address).use { sender ->
        val payload = byteArrayOf(1, 2, 3)
        sender.send(DatagramPacket(payload, payload.size, address, port))
        val packet = withTimeout(5_000) { socket.readPacket() }
        assertEquals(ip, packet.host)
        assertEquals(sender.localPort, packet.port)
      }
    } finally {
      socket.close()
    }
  }

  /** A local IPv4 address whose reverse lookup gives a name. A fresh instance, so no name is cached. */
  private fun namedLocalAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp }
    .flatMap { it.inetAddresses.toList() }
    .filter { it is Inet4Address && !it.isLinkLocalAddress }
    .firstOrNull { InetAddress.getByAddress(it.address).hostName != it.hostAddress }
    ?.let { InetAddress.getByAddress(it.address) }
}
