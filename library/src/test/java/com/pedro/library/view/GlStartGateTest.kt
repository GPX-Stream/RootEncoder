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

package com.pedro.library.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// GPX R45 — covers the serialization logic only. The real GL bring-up cannot run on the plain JVM
// (GlStreamInterface constructs SurfaceManager/MainRender/SensorRotationManager), so what the
// gate's callers do inside doStart/doStop is not exercised here.
class GlStartGateTest {

  private val running = AtomicBoolean(false)
  private val gate = GlStartGate { running.get() }

  @Test
  fun `two concurrent starts perform exactly one real start`() {
    val realStarts = AtomicInteger(0)
    val firstInside = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val doStart = {
      realStarts.incrementAndGet()
      firstInside.countDown()
      // Stay inside long enough that the second caller is certain to arrive while `running` is
      // still false -- the exact window the field logs show.
      releaseFirst.await(2, TimeUnit.SECONDS)
      running.set(true)
    }
    val a = thread { gate.start(doStart) }
    assertTrue(firstInside.await(2, TimeUnit.SECONDS))
    val b = thread { gate.start(doStart) }
    Thread.sleep(100)
    releaseFirst.countDown()
    a.join(3000)
    b.join(3000)
    assertEquals(1, realStarts.get())
    assertTrue(running.get())
  }

  @Test
  fun `a caller that lost the race does not start again after the winner succeeded`() {
    val realStarts = AtomicInteger(0)
    gate.start { realStarts.incrementAndGet(); running.set(true) }
    gate.start { realStarts.incrementAndGet(); running.set(true) }
    assertEquals(1, realStarts.get())
  }

  @Test
  fun `a failed winner surfaces its error and the next caller retries`() {
    val realStarts = AtomicInteger(0)
    var failure: Throwable? = null
    try {
      gate.start { realStarts.incrementAndGet(); throw IllegalStateException("GL init failed") }
    } catch (e: IllegalStateException) {
      failure = e
    }
    assertEquals("GL init failed", failure?.message)
    assertFalse(running.get())
    gate.start { realStarts.incrementAndGet(); running.set(true) }
    assertEquals(2, realStarts.get())
    assertTrue(running.get())
  }

  @Test
  fun `stop waits for a start already in progress`() {
    val order = java.util.Collections.synchronizedList(mutableListOf<String>())
    val startInside = CountDownLatch(1)
    val releaseStart = CountDownLatch(1)
    val a = thread {
      gate.start {
        startInside.countDown()
        releaseStart.await(2, TimeUnit.SECONDS)
        running.set(true)
        order.add("start")
      }
    }
    assertTrue(startInside.await(2, TimeUnit.SECONDS))
    val b = thread {
      gate.stop {
        running.set(false)
        order.add("stop")
      }
    }
    Thread.sleep(100)
    assertTrue("stop must not run while start is inside", order.isEmpty())
    releaseStart.countDown()
    a.join(3000)
    b.join(3000)
    assertEquals(listOf("start", "stop"), order)
    assertFalse(running.get())
  }

  @Test
  fun `start after stop starts fresh`() {
    val realStarts = AtomicInteger(0)
    gate.start { realStarts.incrementAndGet(); running.set(true) }
    gate.stop { running.set(false) }
    gate.start { realStarts.incrementAndGet(); running.set(true) }
    assertEquals(2, realStarts.get())
  }
}
