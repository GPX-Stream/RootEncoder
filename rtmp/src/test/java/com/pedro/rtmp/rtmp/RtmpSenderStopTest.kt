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

package com.pedro.rtmp.rtmp

import com.pedro.common.ConnectChecker
import com.pedro.common.frame.MediaFrame
import com.pedro.rtmp.utils.socket.RtmpSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
class RtmpSenderStopTest {

  /**
   * A socket whose writes, once armed, wait inside the call until [fail] and then throw. The wait
   * cannot be cancelled, like a blocking socket write that returns only when the socket is closed.
   */
  private class BlockingRtmpSocket : RtmpSocket() {
    @Volatile var armed = false
    val inWrite = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Throwable>()

    fun fail(error: Throwable) { release.complete(error) }

    private suspend fun gate() {
      if (!armed) return
      inWrite.complete(Unit)
      throw withContext(NonCancellable) { release.await() }
    }

    override suspend fun write(b: Int) = gate()
    override suspend fun write(b: ByteArray) = gate()
    override suspend fun write(b: ByteArray, offset: Int, size: Int) = gate()
    override suspend fun writeUInt16(b: Int) = gate()
    override suspend fun writeUInt24(b: Int) = gate()
    override suspend fun writeUInt32(b: Int) = gate()
    override suspend fun writeUInt32LittleEndian(b: Int) = gate()
    override suspend fun flush(isPacket: Boolean) {}
    override suspend fun connect() {}
    override suspend fun close() { release.complete(ClosedChannelException()) }
    override fun isConnected(): Boolean = true
    override fun isReachable(): Boolean = true
    override suspend fun read(): Int = throw UnsupportedOperationException()
    override suspend fun readUInt16(): Int = throw UnsupportedOperationException()
    override suspend fun readUInt24(): Int = throw UnsupportedOperationException()
    override suspend fun readUInt32(): Int = throw UnsupportedOperationException()
    override suspend fun readUInt32LittleEndian(): Int = throw UnsupportedOperationException()
    override suspend fun readUntil(b: ByteArray) = throw UnsupportedOperationException()
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

  private suspend fun startSenderWritingOn(socket: BlockingRtmpSocket): RtmpSender {
    val sender = RtmpSender(checker, CommandsManagerImp())
    sender.socket = socket
    sender.setAudioInfo(44100, true)
    sender.start()
    socket.armed = true
    val frame = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    sender.sendMediaFrame(ByteBuffer.wrap(frame), MediaFrame.Info(0, frame.size, 0, true), MediaFrame.Type.AUDIO)
    withTimeout(5_000) { socket.inWrite.await() }
    return sender
  }

  @Test
  fun `a stop while the sender is writing reports nothing`() = runBlocking {
    val socket = BlockingRtmpSocket()
    val sender = startSenderWritingOn(socket)

    // the client closes the socket when the stop's wait runs out; stop() has cleared running by then
    sender.stop(unlockNeeded = { socket.close() })
    delay(200)

    verify(checker, never()).onConnectionFailed(any())
  }

  @Test
  fun `a send fault while the stream runs is reported once`() = runBlocking {
    val socket = BlockingRtmpSocket()
    val sender = startSenderWritingOn(socket)

    socket.fail(IOException("Network is unreachable"))
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
