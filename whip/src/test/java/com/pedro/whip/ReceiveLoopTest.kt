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

import com.pedro.whip.utils.ReceiveLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** GPX R47 — the receive loop reports a fault once, survives a quiet link, and ends quietly on cancel. */
class ReceiveLoopTest {

  @Test
  fun `a handler that throws is reported once and the loop ends`() = runTest {
    var reads = 0
    val faults = mutableListOf<Throwable>()
    ReceiveLoop(
      readOnce = { reads++; throw IOException("read failed") },
      isClosing = { false },
      onFault = { faults += it },
    ).run()
    assertEquals("the loop must not retry a failing read", 1, reads)
    assertEquals(1, faults.size)
    assertEquals("read failed", faults[0].message)
  }

  @Test
  fun `cancellation ends the loop quietly and reports nothing`() = runTest {
    var faults = 0
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      ReceiveLoop(
        readOnce = { awaitCancellation() },
        isClosing = { false },
        onFault = { faults++ },
      ).run()
    }
    yield()
    job.cancel()
    job.join()
    assertTrue(job.isCancelled)
    assertEquals(0, faults)
  }

  @Test
  fun `a failure after we closed the socket is not reported`() = runTest {
    var closing = false
    var faults = 0
    ReceiveLoop(
      readOnce = { closing = true; throw IOException("Socket closed") },
      isClosing = { closing },
      onFault = { faults++ },
    ).run()
    assertEquals(0, faults)
  }

  @Test
  fun `a read timeout is survived and only a later real fault is reported`() = runTest {
    var reads = 0
    val faults = mutableListOf<Throwable>()
    ReceiveLoop(
      readOnce = {
        reads++
        if (reads <= 3) throw SocketTimeoutException("quiet link") else throw IOException("boom")
      },
      isClosing = { false },
      onFault = { faults += it },
    ).run()
    assertEquals(4, reads)
    assertEquals(1, faults.size)
    assertEquals("boom", faults[0].message)
  }

  @Test
  fun `a cancellation raised by the socket while this coroutine is active is a fault`() = runTest {
    val faults = mutableListOf<Throwable>()
    ReceiveLoop(
      readOnce = { throw CancellationException("socket job cancelled") },
      isClosing = { false },
      onFault = { faults += it },
    ).run()
    assertEquals(1, faults.size)
  }
}
