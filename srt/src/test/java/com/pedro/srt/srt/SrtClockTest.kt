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

import android.os.SystemClock
import com.pedro.common.ConnectChecker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock

/**
 * GPX R49 — SRT's silence timer and handshake deadline read elapsed realtime, which a wall-clock
 * step cannot move.
 */
class SrtClockTest {

  @Test
  fun `the client's clock is elapsed realtime, not the wall clock`() {
    mockStatic(SystemClock::class.java).use { clock ->
      clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(42_000L)
      val client = SrtClient(mock<ConnectChecker>())
      assertEquals(42_000L, client.nowMs())
      clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(43_500L)
      assertEquals(43_500L, client.nowMs())
    }
  }
}
