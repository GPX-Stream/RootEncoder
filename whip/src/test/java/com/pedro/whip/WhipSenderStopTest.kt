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
import com.pedro.common.frame.MediaFrame
import com.pedro.common.socket.base.UdpPacket
import com.pedro.common.socket.base.UdpStreamSocket
import com.pedro.whip.webrtc.CommandsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

/**
 * GPX R61 — a send that fails because the stop closed the connection is not a fault. A send fault
 * while the sender is still meant to run is reported once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhipSenderStopTest {

  /**
   * A media socket whose `write` waits inside the call until [close] (the stop) or [failNow], then
   * throws, like a send on a channel that a stop closed under it.
   */
  private class FakeMediaSocket : UdpStreamSocket() {
    val inWrite = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Throwable>()

    fun failNow(error: Throwable) { release.complete(error) }

    override suspend fun write(bytes: ByteArray) {
      inWrite.complete(Unit)
      throw release.await()
    }

    // The write fails as the close starts and the close returns a little later, so the sender's
    // error path runs before stop() cancels the sender job, as it does on a real socket.
    override suspend fun close() {
      release.complete(ClosedChannelException())
      delay(300)
    }

    override suspend fun bind() {}
    override suspend fun write(bytes: ByteArray, host: String, port: Int) = write(bytes)
    override suspend fun read(): ByteArray = ByteArray(0)
    override suspend fun readPacket(): UdpPacket = throw UnsupportedOperationException()
    override suspend fun setRemoteAddress(host: String, port: Int) {}
    override suspend fun getLocalHost(): String = "127.0.0.1"
    override suspend fun getLocalPort(): Int = 0
    override suspend fun connect() {}
    override fun isConnected(): Boolean = true
    override fun isReachable(): Boolean = true
  }

  private val checker: ConnectChecker = mock()

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private suspend fun startSenderWritingOn(socket: FakeMediaSocket): WhipSender {
    val sender = WhipSender(checker, CommandsManager())
    sender.setSocketsInfo(socket)
    sender.start()
    val frame = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    sender.sendMediaFrame(ByteBuffer.wrap(frame), MediaFrame.Info(0, frame.size, 0, true), MediaFrame.Type.AUDIO)
    withTimeout(5_000) { socket.inWrite.await() }
    return sender
  }

  @Test
  fun `a stop while the sender is writing reports nothing`() = runBlocking {
    val socket = FakeMediaSocket()
    val sender = startSenderWritingOn(socket)

    // stop() closes the socket under the write in flight, which throws ClosedChannelException
    sender.stop()

    verify(checker, never()).onConnectionFailed(any())
  }

  @Test
  fun `a send fault while the stream runs is reported once`() = runBlocking {
    val socket = FakeMediaSocket()
    val sender = startSenderWritingOn(socket)

    socket.failNow(IOException("Network is unreachable"))
    withTimeout(5_000) {
      while (true) {
        try {
          verify(checker).onConnectionFailed("Error send packet, Network is unreachable")
          break
        } catch (_: AssertionError) {
          delay(20)
        }
      }
    }
    sender.stop()

    // the stop that follows the fault adds no second report
    verify(checker).onConnectionFailed(any())
  }
}
