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

package com.pedro.srt.srt

import com.pedro.common.ConnectChecker
import com.pedro.common.frame.MediaFrame
import com.pedro.srt.srt.packets.SrtPacket
import com.pedro.srt.utils.SrtSocket
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
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

/**
 * GPX R61 — a send that fails because the stop closed the connection is not a fault. A send fault
 * while the sender is still meant to run is reported once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SrtSenderStopTest {

  private val checker: ConnectChecker = mock()

  @Volatile private var armed = false
  private val inWrite = CompletableDeferred<Unit>()
  private val release = CompletableDeferred<Throwable>()

  /** Writes pass until armed; an armed write waits, uncancellably, until [release] and then throws. */
  private fun blockingSocket(): SrtSocket = mock<SrtSocket>().also { socket ->
    runBlocking {
      whenever(socket.write(any<SrtPacket>())).doSuspendableAnswer {
        if (armed) {
          inWrite.complete(Unit)
          throw withContext(NonCancellable) { release.await() }
        }
      }
    }
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private suspend fun startSenderWriting(): SrtSender {
    val sender = SrtSender(checker, CommandsManager())
    sender.socket = blockingSocket()
    sender.setAudioInfo(44100, true)
    sender.start()
    delay(200) // the config packets go out first, unarmed
    armed = true
    val frame = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    sender.sendMediaFrame(ByteBuffer.wrap(frame), MediaFrame.Info(0, frame.size, 0, true), MediaFrame.Type.AUDIO)
    withTimeout(5_000) { inWrite.await() }
    return sender
  }

  @Test
  fun `a stop while the sender is writing reports nothing`() = runBlocking {
    val sender = startSenderWriting()

    // the client closes the socket after stop() has cleared running
    sender.stop(unlockNeeded = { release.complete(ClosedChannelException()) })
    delay(200)

    verify(checker, never()).onConnectionFailed(any())
  }

  @Test
  fun `a send fault while the stream runs is reported once`() = runBlocking {
    val sender = startSenderWriting()

    release.complete(IOException("Network is unreachable"))
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

    verify(checker).onConnectionFailed(any())
  }
}
