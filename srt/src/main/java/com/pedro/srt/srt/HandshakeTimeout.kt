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

// GPX R8 — handshake retransmit backoff, in milliseconds, and also the socket read timeout during
// the handshake, which sets the poll granularity.
//
// Sending each handshake once and block-reading the whole latency-derived socketTimeout makes one
// lost UDP packet cost the entire window, which surfaces as "Poll timed out". Re-knocking fixes
// that, and the gap grows rather than staying fixed because two failure modes pull opposite ways.
// A cold lost packet wants a fast re-knock, within about 250 ms. A server holding a prior session
// after a relaunch releases it during a lull: continuous 250 ms knocking was observed riding an
// 11 s window with no response, and the knock that latched was the one after a multi-second
// silent gap. Growing the gap covers the lost packet early and provides the quiet windows later.
internal const val HANDSHAKE_RETRANSMIT_MS = 250L
internal const val HANDSHAKE_RETRANSMIT_CAP_MS = 2_000L

/**
 * GPX R46 — the SRT client's socket timeout, in milliseconds, for a given SRT latency.
 *
 * [latencyMs] is in milliseconds, the unit [CommandsManager.latency] and the connect URL's
 * `latency` query both use. The timeout is that latency plus one second of headroom. Once
 * connected it is the receive loop's read timeout, and it is the starting point for the
 * handshake budget ([handshakeBudgetMsFor]).
 *
 * A negative latency (a hostile or malformed `latency=` value) is treated as zero, so the result
 * is never below the one second of headroom.
 */
internal fun socketTimeoutMsFor(latencyMs: Int): Long = latencyMs.coerceAtLeast(0) + 1_000L

/**
 * GPX R48 — when R8's handshake schedule sends its first knock after a full capped gap:
 * knocks go out at 0, 250, 750, 1750 and 3750 ms, so this is 3750. That knock is the one R8 exists
 * for, the one a server holding a prior session answers after a quiet spell.
 */
internal fun firstCappedKnockMs(): Long {
  var at = 0L
  var gap = HANDSHAKE_RETRANSMIT_MS
  while (true) {
    at += gap
    if (gap >= HANDSHAKE_RETRANSMIT_CAP_MS) return at
    gap = (gap * 2).coerceAtMost(HANDSHAKE_RETRANSMIT_CAP_MS)
  }
}

/**
 * GPX R48 — the least total time the handshake gets: the first knock after a capped gap, plus one
 * poll window for its reply to arrive. 4000 ms with R8's current constants.
 */
internal val HANDSHAKE_BUDGET_FLOOR_MS: Long = firstCappedKnockMs() + HANDSHAKE_RETRANSMIT_MS

/**
 * GPX R48 — the total time [SrtClient] spends on the two-phase handshake: the latency-derived
 * timeout, but never less than [HANDSHAKE_BUDGET_FLOOR_MS], so R8's backoff always reaches its
 * capped gap. Without the floor, any latency under about 3 s cut the handshake off before that
 * knock was sent.
 */
internal fun handshakeBudgetMsFor(latencyMs: Int): Long =
  maxOf(socketTimeoutMsFor(latencyMs), HANDSHAKE_BUDGET_FLOOR_MS)
