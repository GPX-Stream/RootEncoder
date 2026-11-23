package com.pedro.common.base

/**
 * GPX R65 — the state a client needs so that a `connect()` that arrives while a stop is running is
 * remembered and run after the teardown, instead of being taken as a fresh connect.
 *
 * What it holds, all behind one plain monitor:
 * - a stop count, not a flag: with two stops running, the first one ending must not clear it;
 * - the generation, raised by every stop and every teardown, so a connect job of an older
 *   generation can tell it is stale and write nothing;
 * - the latest deferred connect url (the latest wins), dropped by a later stop (the stop wins).
 *
 * The monitor is never held across a suspension, a callback or a join, so it cannot deadlock with
 * the main-thread hop that `onConnectionStarted` and `onDisconnect` make. [locked] is for the
 * client's own check-then-act (the connect admission, the scope replacement); keep what runs inside
 * it short and non-blocking.
 *
 * Fork plan entry: R65.
 */
class ConnectGate {

  @PublishedApi
  internal val lock = Any()

  @Volatile
  var generation = 0L
    private set

  private var stops = 0
  private var hasDeferred = false
  private var deferredUrl: String? = null

  inline fun <T> locked(block: () -> T): T = synchronized(lock) { block() }

  /** True while a user stop is running, from [beginStop] to its [endStop]. */
  fun stopping(): Boolean = synchronized(lock) { stops > 0 }

  fun isCurrent(generation: Long): Boolean = this.generation == generation

  /** A stop starts: counted, the generation moves on, and a connect deferred earlier is dropped. */
  fun beginStop() = synchronized(lock) {
    stops++
    generation++
    hasDeferred = false
    deferredUrl = null
  }

  /** A teardown that is not a user stop (a retry's) starts: connect jobs of the old generation go stale. */
  fun supersede() = synchronized(lock) {
    generation++
  }

  /** Remember a connect that arrived while stopping. The latest one wins. */
  fun defer(url: String?) = synchronized(lock) {
    hasDeferred = true
    deferredUrl = url
  }

  /**
   * A stop ends. When it was the last one running and a connect is deferred, [startDeferred] runs
   * with its url inside the monitor, so a `connect()` arriving at this moment cannot get in between.
   */
  fun endStop(startDeferred: (String?) -> Unit) = synchronized(lock) {
    if (stops > 0) stops--
    if (stops == 0 && hasDeferred) {
      val url = deferredUrl
      hasDeferred = false
      deferredUrl = null
      startDeferred(url)
    }
  }

  companion object {
    /**
     * GPX R65 — how long a stop waits for the connect jobs of its scope to end after cancelling
     * them. A job stuck in a call that does not react to cancellation must not hang the stop; the
     * generation makes such a job harmless when it wakes up. Not a dashboard setting: nothing the
     * operator changes can move it. It matches the wait `BaseSender.stop` gives its own job.
     */
    const val CONNECT_JOIN_TIMEOUT_MS = 1_000L
  }
}
