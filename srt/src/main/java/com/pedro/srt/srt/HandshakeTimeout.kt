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

/**
 * GPX R46 — the SRT client's socket timeout, in milliseconds, for a given SRT latency.
 *
 * [latencyMs] is in milliseconds, the unit [CommandsManager.latency] and the connect URL's
 * `latency` query both use. The timeout is that latency plus one second of headroom. It is the
 * total budget [SrtClient] spends on the two-phase handshake (R8's re-knocks run inside it) and,
 * once connected, the read timeout of the receive loop.
 *
 * A negative latency (a hostile or malformed `latency=` value) is treated as zero, so the result
 * is never below the one second of headroom.
 */
internal fun socketTimeoutMsFor(latencyMs: Int): Long = latencyMs.coerceAtLeast(0) + 1_000L
