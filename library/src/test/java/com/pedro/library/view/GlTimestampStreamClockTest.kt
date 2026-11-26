package com.pedro.library.view

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPX R66 — the stamp each drawn frame gets, which the video encoder counts from the origin. It follows
 * the device clock however the picture's own stamps behave, so a camera switch or a slate drawn over a
 * frozen camera picture cannot move the stream clock: never backwards, and within the drift bound.
 */
class GlTimestampStreamClockTest {

  private val stamps = GlTimestamp()
  private var clockNs = 3_600_000_000_000L
  private var previous = 0L

  private fun draw(sourceNs: Long, afterNs: Long = FRAME_NS) {
    clockNs += afterNs
    val stamp = stamps.getTimestamp(sourceNs, clockNs)
    assertTrue("went back: $previous to $stamp", stamp >= previous)
    assertTrue("off the clock by ${stamp - clockNs}", Math.abs(stamp - clockNs) <= DRIFT_NS)
    previous = stamp
  }

  @Test
  fun `a switch to a camera on another time base stays on the clock`() {
    var source = 9_000_000_000_000L
    repeat(30) { draw(source.also { source += FRAME_NS }) }
    // A camera whose stamps are hours behind, then one hours ahead.
    var behind = 1_000_000L
    repeat(30) { draw(behind.also { behind += FRAME_NS }) }
    var ahead = 90_000_000_000_000L
    repeat(30) { draw(ahead.also { ahead += FRAME_NS }) }
  }

  @Test
  fun `forced draws over a camera that sends nothing follow the clock, and so does its return`() {
    var source = 9_000_000_000_000L
    repeat(30) { draw(source.also { source += FRAME_NS }) }
    val frozen = source
    repeat(300) { draw(frozen) }
    // A gap with no draw at all.
    draw(frozen, afterNs = 20_000_000_000L)
    var back = 500L
    repeat(30) { draw(back.also { back += FRAME_NS }) }
  }

  private companion object {
    const val FRAME_NS = 33_333_333L
    const val DRIFT_NS = 100_000_000L
  }
}
