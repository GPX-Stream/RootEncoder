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

/**
 * GPX R45 — serializes [GlStreamInterface.start] and [GlStreamInterface.stop] against each other.
 *
 * `start()` is not re-entrant: a second call rebuilds the executor, EGL surface manager and handler
 * thread, which kills the first caller's in-flight GL-init task. The callers guard it with
 * `if (!glInterface.isRunning) glInterface.start()`, but `running` only turns true at the end of the
 * init task, so two threads can both pass that check (the preview call on Main and the record lane's
 * `startSources`). Holding one monitor across the start body, and re-checking [isRunning] inside it,
 * turns the second caller into a waiter: it blocks until the winner's init is done, sees the GL
 * running, and returns without touching it. If the winner failed, `running` is still false and the
 * waiter makes its own attempt, so a genuine failure still throws to whoever tried.
 *
 * [stop] shares the monitor so it cannot interleave with a start still initializing.
 *
 * Both waits are bounded by what `start()` already bounds (the previous release wait plus the GL-init
 * wait), so a caller on Main waits no longer than it did as the sole caller. The monitor is never held
 * while calling out of `GlStreamInterface`, and nothing on the GL thread takes it.
 *
 * Kept separate from `GlStreamInterface` so the serialization is testable on the plain JVM, which
 * cannot construct the GL classes.
 */
internal class GlStartGate(private val isRunning: () -> Boolean) {

  private val monitor = Any()

  /** Runs [doStart] unless the GL is already running. Blocks while another start or stop runs. */
  fun start(doStart: () -> Unit) {
    synchronized(monitor) {
      if (isRunning()) return
      doStart()
    }
  }

  /** Runs [doStop]. Blocks while another start or stop runs. */
  fun stop(doStop: () -> Unit) {
    synchronized(monitor) {
      doStop()
    }
  }
}
