package com.pedro.library.base

/**
 * GPX R66 — which start timestamp [StreamBase] hands its encoders. Every encoded timestamp is the
 * frame's clock minus this value, on the [com.pedro.common.TimeUtils] clock.
 *
 * With no origin, each start counts from itself, so timestamps begin again near zero. With one, every
 * start counts from the origin. The caller holds the origin, so it can give the same one to a new
 * StreamBase, which an encoder-held baseline (R9) cannot do.
 */
internal class StreamClock {

  // Volatile: set on the caller's thread, read by whichever thread starts the sources.
  @Volatile
  private var originUs = 0L

  /** See [StreamBase.setTimestampOrigin]. */
  fun setOrigin(originUs: Long, nowUs: Long) {
    require(originUs in 0..nowUs) { "timestamp origin $originUs must be between 0 and now ($nowUs)" }
    this.originUs = originUs
  }

  fun startTimestampUs(nowUs: Long): Long {
    // One read: a setOrigin(0) between two would return 0.
    val origin = originUs
    return if (origin > 0) origin else nowUs
  }
}
