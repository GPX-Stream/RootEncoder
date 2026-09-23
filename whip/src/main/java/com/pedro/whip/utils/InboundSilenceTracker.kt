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

import com.pedro.common.TimeUtils

/**
 * GPX R42 — how long the WHIP server has gone without sending a media-plane packet back.
 *
 * A send-only publisher never consumes what the server sends on the media plane, but it still
 * receives the server's RTCP: its receiver reports on the stream. Their arrival shows the ingest
 * is actually receiving the media, which an outbound byte counter cannot show — a socket keeps
 * accepting sends while the far end has stopped relaying. STUN binding traffic is deliberately not
 * counted here: it proves only that the ICE session is held, not that media is arriving.
 *
 * The clock is [TimeUtils.getCurrentTimeMillis] (elapsed realtime, monotonic), so a wall-clock
 * adjustment on the device cannot fake or hide a silence. [nowMs] is a parameter only so tests
 * can drive time.
 */
class InboundSilenceTracker(private val nowMs: () -> Long = { TimeUtils.getCurrentTimeMillis() }) {

  /** When the last inbound media-plane packet arrived, or when the session started; null before. */
  @Volatile
  private var lastInboundMs: Long? = null

  /**
   * Starts the silence clock at session establishment, so an ingest that never sends anything
   * reads as growing silence from the moment media starts flowing rather than as "unknown" forever.
   */
  fun start() {
    lastInboundMs = nowMs()
  }

  /** Records one inbound media-plane packet. */
  fun onInbound() {
    lastInboundMs = nowMs()
  }

  /** Clears the clock when the session is torn down, so a retry cannot inherit a stale reading. */
  fun reset() {
    lastInboundMs = null
  }

  /** Milliseconds since the last inbound media-plane packet (or [start]), or -1 before [start]. */
  fun silenceMs(): Long = lastInboundMs?.let { nowMs() - it } ?: -1L
}
