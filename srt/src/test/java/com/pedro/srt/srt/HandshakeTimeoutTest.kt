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

import org.junit.Assert.assertEquals
import org.junit.Test

/** GPX R46 — the socket timeout is latency in milliseconds plus one second. */
class HandshakeTimeoutTest {

  @Test
  fun `a two second latency gets a three second timeout, not one`() {
    // The unit bug this guards: latency / 1000 gave 1002 here.
    assertEquals(3_000L, socketTimeoutMsFor(2_000))
  }

  @Test
  fun `the library default latency`() {
    assertEquals(1_120L, socketTimeoutMsFor(CommandsManager().latency))
  }

  @Test
  fun `zero latency keeps the one second headroom`() {
    assertEquals(1_000L, socketTimeoutMsFor(0))
  }

  @Test
  fun `the ceiling latency does not overflow`() {
    assertEquals(31_000L, socketTimeoutMsFor(30_000))
    assertEquals(Int.MAX_VALUE + 1_000L, socketTimeoutMsFor(Int.MAX_VALUE))
  }

  @Test
  fun `a negative latency from a malformed url never drops below the headroom`() {
    assertEquals(1_000L, socketTimeoutMsFor(-5))
    assertEquals(1_000L, socketTimeoutMsFor(Int.MIN_VALUE))
  }
}
