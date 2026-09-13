# Upstream sync 2026-09-13 — overlap analysis

`master` was fast-forwarded to `upstream/master` @ `620d05ffb` (8 commits past the fork's last
full-sync point, `1285703b`/R35). This is not a `gpx-2.8` sync — `gpx-2.8` still merges upstream
in periodic batches (R26, R27, R31, R35) with its own bench-gated tag. This doc covers the 4
non-merge commits in that batch, each checked against work this fork already shipped for the
same or an adjacent problem.

## 1. SRT dead-link detection — `442ee5b58` vs `GPX R7`

**Upstream (`442ee5b58`, "srt: optional server silence timeout to detect a dead path"):**
`SrtStreamClient.setServerSilenceTimeout(millis)` (opt-in, 0 = disabled) tracks
`lastServerPacketTs`, updated on every successful `handleMessages()` read inside
`handleServerPackets()`'s loop. The elapsed-time check itself only runs in the loop's `else if`
branch — reached exactly when the last read attempt threw and that throw was a
`SocketTimeoutException` (any other exception already cancels the scope on the branch above it).

**Ours (`GPX R7`, `srt/src/main/java/com/pedro/srt/srt/SrtClient.kt`):** a dedicated coroutine
(`startInboundSilenceWatchdog`) ticks every 1000ms independently of the read loop, comparing
`lastInboundMs` (updated on every successful read, same as upstream's `lastServerPacketTs`)
against a fixed 5500ms threshold, and reports `onConnectionFailed` itself.

**Mechanism — R7 is the more robust design.** Upstream's check only evaluates when the blocking
`socket.readBuffer()` call actually throws `SocketTimeoutException`. That throw is driven by the
socket's own read timeout (`socketTimeout`, latency-derived: `(latency/1000)+1000` ms — as low as
~1000ms at the SRT default 120ms latency, or several seconds at a higher configured latency), so
upstream's detection cadence is coupled to a value tuned for the handshake/read-retry logic, not
to the silence window it's supposedly watching. Worse, if the underlying socket ever fails to
honor its read timeout — a real class of platform/VPN-interposition bug, not hypothetical — the
blocking read never throws, `handleServerPackets()` never reaches the check, and the "opt-in"
watchdog does nothing despite being enabled. R7's tick runs on its own coroutine and never touches
the blocking read at all, so it fires regardless of whether the read timeout mechanism is working.

**Edge cases — both handle them.** First-packet-before-timestamp-set: R7 initializes
`lastInboundMs` before starting the watchdog and the watchdog also no-ops on `0L`; upstream
initializes `lastServerPacketTs` locally at the top of `handleServerPackets()`. Reconnect/retry:
both re-initialize fresh on each call (R7 via `connect()` re-running the same init+start sequence;
upstream because the variable is function-local). No advantage either way.

**Configurability — a wash for this consumer, not a gap.** `gpxstream-app` never reads R7's fixed
5500ms threshold or its `onConnectionFailed` report as its primary liveness signal — it reads the
raw counter directly (`StreamBaseClient.getInboundSilenceMs()`, `drivers/LivenessSampler.kt`) and
judges it against its own tunable, protocol-aware window
(`livenessStallGraceMs`: `max(12000, srtLatency + 2000)` for SRT). So the app already has the
configurability upstream is adding, sitting one layer above R7's raw counter — it does not need
`SrtClient` itself to expose a settable threshold. **Worth noting, not fixing:** R7's own internal
watchdog fires at a fixed 5500ms — faster than the app's 12000ms+ floor — so a link failure is
very likely reported through R7's `onConnectionFailed` path before the app's own
`LivenessSampler` ever sees it. That's intentional per R7's comment (tuned to an observed ~5s
server-side drop), not a bug, but it means two different code paths can both fire for what is
conceptually one fault, on two different timers. No action needed unless a real false positive or
duplicate-report issue shows up in the field.

**Recommendation: keep R7 as-is, do not adopt upstream's mechanism, do not add configurability
speculatively.** R7 is architecturally sounder for the specific failure it targets, and there is
no demonstrated need driving a configurability change — adding one now would be exactly the
speculative fork surface the project's own rules ask to avoid. If a real link ever needs a looser
or tighter internal-watchdog threshold than 5500ms, that's the trigger to revisit this, not before.

## 2. Sender stuck in a blocking socket write on disconnect — `f1c980001` + `cf419bd21`

**Upstream:** `BaseSender.stop()`'s `job?.cancelAndJoin()` doesn't return while the sender
coroutine is blocked in a `java.io` socket write under TCP backpressure — `cancel()` doesn't
interrupt a blocking write. Fix (two commits, second one refactors the first into `BaseSender`
itself): try a cooperative stop bounded to 1000ms; if it doesn't finish, close the socket (which
unblocks the write with an `IOException`) and try again, bounded again. Lands in
`BaseSender.kt`, `RtmpClient.kt`, `RtspClient.kt`. SRT is unaffected — UDP sends don't block on
ACKs.

**Ours:** nothing existing covers this. R29 bounds a different blocking join
(`AsyncBaseRecordController.stopRecord`'s muxer-coroutine join, for a wedged *disk* write); R30
adds lifecycle locking around `StreamBase`'s source/encoder mutations. Neither touches
`BaseSender.stop()`'s sender-job join, which had exactly R29's original defect
(`job?.cancelAndJoin()`, unbounded, on a blocking call cancellation can't interrupt) sitting
unpatched on the network-send side.

**Recommendation: adopt.** Same defect class R29 already established is worth fixing here, on a
different, non-overlapping code path (network sender teardown, not the recording muxer). No GPX
markers exist anywhere near the touched lines in `BaseSender.kt`/`RtmpClient.kt`/`RtspClient.kt`,
so this is a clean, no-conflict adoption of upstream's own fix. Cherry-picked as-is (see
"Implemented" below) — the code is upstream's, not fork-authored, so per the fork's own
convention for adopted-upstream code (`.claude/gpx-branch-policy.md`, the R26/R27/R31/R35 merge
entries), it carries no inline `GPX R<N>` marker; it's tracked in
`.claude/gpx-reapply-plan-2.8.0.md` as R39 instead.

## 3. `stopRecord` deadlock hardening — `9a0cd3305`

**Upstream:** four changes bundled as one fix:
- `AsyncBaseRecordController`'s muxer-drain loop gains `if (!isActive) break` before
  `onWriteFrame(frame)` — stops it from starting a fresh write on an already-cancelled job.
- `AsyncBaseRecordController.stopRecord`'s bounded join drops from unbounded (pre-R29 upstream)
  to a 1000ms `withTimeoutOrNull` — the same shape R29 already shipped, at a different bound.
- `BitrateManager.calculateBitrate` and `StreamingStatsMonitor` switch their bitrate/stats
  callback dispatch from `onMainThread` (`suspend fun`, `withContext(Dispatchers.Main)` — waits
  for the main-thread post to actually run) to `onMainThreadHandler` (fire-and-forget
  `Handler.post()`).
- `onMainThreadHandler` itself gains a null-`Looper` guard (`Looper.getMainLooper()` can return
  null; runs the code inline instead of NPE-ing on `Handler(null)`).

**Why this is titled a deadlock fix:** `stopRecord()` calls its muxer join from inside
`runBlocking`, which — if `stopRecord()` runs on the main thread, as a UI-triggered stop
typically does — occupies the main thread for the join's duration. A concurrent coroutine
suspended in the old, waiting-variant `onMainThread { ... }` (e.g. a bitrate report in flight on
the sender path) needs the main thread's looper to actually run its posted block before it can
resume — but the main thread is the one now blocked inside `stopRecord`'s `runBlocking`. Switching
those two call sites to fire-and-forget removes that suspend-and-wait circularity.

**Ours:** R29 already bounds the muxer join (at 3000ms, not upstream's 1000ms — kept as-is, see
below). Nothing in the fork touches the `onMainThread`/`onMainThreadHandler` split or the
in-loop `isActive` check — this is a real, additional class of hardening R29/R30 don't cover.

**Recommendation: adopt the three parts R29 doesn't already cover** (the `isActive` early-exit,
the `onMainThreadHandler` fire-and-forget swap in `BitrateManager`/`StreamingStatsMonitor`, and
the null-`Looper` guard). **Do not adopt the 1000ms join bound** — R29's 3000ms is already
authorized (`gpxstream-app` S8 post-merge review, F1/CRITICAL) and bench-verified
(`2.8.0-gpx3`); narrowing it to match upstream is a real behavior change to an already-shipped,
already-tuned constant, not a mechanical adoption, and needs its own reason if it's ever
revisited — not bundled into an unrelated sync. Resolved the cherry-pick conflict on that one
line in R29's favor; the rest of the commit applied clean. No GPX markers existed on any of the
other touched lines. Tracked as R40 in `.claude/gpx-reapply-plan-2.8.0.md` (no inline marker,
same convention as item 2 — adopted upstream code, not fork-authored).

## 4. `1d3d88d91` — keep encoders running in `stopStream` while a recording is starting/paused

**Upstream:** `Camera1Base`, `Camera2Base`, `DisplayBase`, `FromFileBase`, `OnlyAudioBase` all
guarded their `stopStream()` teardown with `if (!recordController.isRecording())` — which is
`false` (i.e., "not recording," so tear everything down) while a recording is in `STARTING` or
`PAUSED` state, since `isRecording()` only returns true for the `RECORDING` status specifically.
Fixed by switching the guard to `isRunning()` (`STARTED || RECORDING || RESUMED || PAUSED`), which
is what "is there a recording in progress that stopping the stream shouldn't kill" actually means.

**Ours:** not applicable. `gpxstream-app` does not use `Camera1Base`/`Camera2Base`/`DisplayBase`/
`FromFileBase`/`OnlyAudioBase` at all — grepped the app repo, zero references. It builds
exclusively on `StreamBase`/`SwitchableStream` (R33). And `StreamBase.kt`'s own `isRecording`
property is already defined as `recordController.isRunning()`
(`library/src/main/java/com/pedro/library/base/StreamBase.kt:189-190`) — the exact fix upstream
just applied to the legacy classes is already how the modern path our fork/consumer actually uses
has always worked. R28's per-encoder re-prepare solves a different, unrelated problem (changing
stream parameters without dropping a live recording) and has no bearing here.

**Recommendation: no action.** This lands automatically, harmlessly, at the next full `gpx-2.8`
sync (touches files we don't use); no reason to cherry-pick it ahead of that, and no bench watch
item — the code path it fixes doesn't exist in what the consumer builds.

## Implemented

Cherry-picked onto `feat/adopt-upstream-teardown-hardening` (worktree off `gpx-2.8`):
`f1c980001`, `cf419bd21`, `9a0cd3305`, with the one-line conflict in
`AsyncBaseRecordController.stopRecord` resolved to keep `STOP_JOIN_TIMEOUT_MS` (R29, 3000ms)
rather than upstream's 1000ms. `gradlew clean assembleDebug test` green across every module and
the sample app. Recorded in `.claude/gpx-reapply-plan-2.8.0.md` as R39 (sender-unblock-on-
disconnect) and R40 (stopRecord deadlock hardening). No tag cut, no pin move — per standing
policy, those are their own separately-authorized step.

**Not implemented, no action needed:** items 1 and 4 above (SRT silence timeout, legacy
`*Base.java` `isRecording`/`isRunning` fix) — both are "keep what we have" / "not applicable"
conclusions, not deferred work.
