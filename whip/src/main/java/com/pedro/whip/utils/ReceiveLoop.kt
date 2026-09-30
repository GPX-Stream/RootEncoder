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

package com.pedro.whip.utils

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.net.SocketTimeoutException

/**
 * GPX R47 — the WHIP client's receive loop, with its failure handling in one testable place.
 *
 * [readOnce] reads and dispatches one datagram. The loop ends in exactly one of three ways:
 *
 * - **Our own cancellation:** ends quietly. The cancellation is rethrown, so the caller's job
 *   finishes as cancelled and nothing is reported.
 * - **The socket was closed by us** ([isClosing] is true when the read fails): ends quietly.
 *   A disconnect closes the socket before it cancels the job, so the read fails first.
 * - **Anything else:** [onFault] is called once and the loop ends. A read on a broken socket fails
 *   at once every time, so carrying on would spin. The caller's reaction to [onFault] (the
 *   consumer's reconnect) tears the session down and starts a new loop.
 *
 * A [SocketTimeoutException] is the one fault survived: a socket with a read timeout raises it
 * after a quiet spell, and a quiet link is not a dead reader. A quiet ingest is judged by the
 * inbound-silence clock (R42), not by ending the reader, which would also stop answering the
 * server's STUN checks.
 */
internal class ReceiveLoop(
  private val readOnce: suspend () -> Unit,
  private val isClosing: () -> Boolean,
  private val onFault: suspend (Throwable) -> Unit,
) {

  suspend fun run() {
    while (currentCoroutineContext().isActive) {
      try {
        readOnce()
      } catch (_: SocketTimeoutException) {
        // quiet link: read again
      } catch (e: Exception) {
        // Cancellation is an Exception too. If it is ours, propagate it; if it came from the
        // socket's own machinery while this coroutine is still active, it is a fault like any other.
        currentCoroutineContext().ensureActive()
        if (!isClosing()) onFault(e)
        return
      }
    }
  }
}
