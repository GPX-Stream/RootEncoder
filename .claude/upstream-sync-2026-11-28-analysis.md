# Upstream sync assessment, 2026-11-28

Compared `origin/gpx-alpha1` (`ed1366560`, the SHA `gpxstream-app` main pins) with `pedro/master`
(`5dcb864ca`). Merge base `f1bbda7ca`, the R52 sync point. `gpx-alpha1` is 122 ahead, 22 behind.
11 non-merge commits, 17 files, +444/-53. Next free item number: R67. No open PRs on the fork.

**Verdict: take it. No GPX patch is replaced. Two merge conflicts, both small.**
Written before the merge: at that point nothing had been merged, built or pushed. Andy approved the
plan on 2026-11-28, and the outcome is the R67 entry in the re-apply plan, which also fixed the skill and
policy text listed at the end. Everything below is from reading diffs and code, plus
`git merge-tree`.

## Commits

| Commit | What | Reaches the app? |
|---|---|---|
| `2a28e943f` | Record muxer job starts after `startRecordImp`; `muxerChannel` volatile; AAC/MP4/WebM muxer init and stop fixes | **Yes** for the base class. The AAC/MP4/WebM parts no (app uses `Mpeg2TsMuxerRecordController`) |
| `a9d97bf5f` | Executor swap and teardown under `sync`; `ForceRenderer` cancels the old loop and rechecks before drawing | **Yes** (app uses force render through `ForcedDrawOwner`) |
| `ee323345f` | `ArrayList(values)` for the multi-preview snapshot | No. App has no multi-preview caller. Harmless |
| `ef72868b3` | `UrlParser.getAppName()` keeps empty path segments | RTMP only, and only for URLs with `//` or a trailing slash |
| `259309b77`, `1279e8b80` (+ merge fix `c37420ae4`) | `Camera2Source` pause/resume repeating, capture template, request customizer | No. App calls none; default stays `TEMPLATE_RECORD` |
| `dfaeec875` | BouncyCastle 1.84 -> 1.86 | **Yes**: `whip` DTLS |
| `fe7d8bcfa` | Kotlin 2.4.10 -> 2.4.20 | Build only |
| `b713d9bc4` | media3-inspector 1.11.1 | No. Only `extra-sources` uses it |
| `dffd9570d` | version 2.8.2 | No |

## Why take it

1. **Record start race (from code reading, never reproduced).** Today the muxer coroutine starts
   before `startRecordImp`, with status already `STARTED`. In our TS muxer, `startRecordImp` replaces
   `videoPacket`/`audioPacket`, resets `sendInfo`, and writes PSI on the caller thread. A frame from a
   live encoder (for example at a rollover while streaming) can run through `onWriteFrame` at the same
   time. Upstream now drops frames until `startRecordImp` returns. R59 (wait for a keyframe) and R4
   (request one after start) already cover the dropped frames.
2. **Force-render / GL teardown crash (from code reading).** Our `stopLocked()` calls
   `executor.shutdown()` and only nulls the field after `awaitTermination` (up to 300 ms). The
   force-render callback and `onFrameAvailable` can `execute()` on that shut-down executor in between,
   which throws `RejectedExecutionException`. On the force-render path it is on an IO coroutine with
   no catch. Upstream takes and nulls the executor under `sync` first.

## Regression risk

- BouncyCastle 1.86: lands in `whip` DTLS. Correction made during the merge: this file first said no
  test runs a DTLS handshake. `WhipConnectStopRaceTest` does, against a fake ingest. It failed 14
  of 15 on both the merge and the unmerged base; on the merge the failures stop at the STUN check,
  before DTLS. The build result is in the policy file's "Verification status".
- `UrlParser`: the app's derived Millicast RTMP URL (`MillicastUrlBuilder`) has no empty segment
  (name and token are percent-encoded), so by reading it parses the same. An explicit user-supplied
  `rtmp://` endpoint with `//` or a trailing slash splits differently. SRT and WHIP call
  `getFullPath()`/`getQuery()`, which did not change.
- Kotlin 2.4.20 vs the app's 2.4.10: expected fine for the JitPack binary (same minor; inference).
  Check the `rootencoder.local` composite build at the pin move, or bump the app too.
- A `stopRecord` on another thread during `startRecordImp` would now leave one suspended coroutine
  behind. The app runs both on its record lane (not re-verified in this pass). Low.

## Per-item verdicts: nothing retires

| File | GPX items in it | Verdict |
|---|---|---|
| `AsyncBaseRecordController.kt` | R12, R29 | Keep. Upstream has no byte counter or rollover; its change does not bound the join. R40's `isActive` exit is in upstream's new function |
| `GlStreamInterface.kt` | R5, R11, R16, R32, R34, R41, R45, R50, R51, R54, R55, R56 | Keep all. Upstream's `sync` guards frame submit against the executor swap. R45 serializes `start()`/`stop()`. R16 waits for and tracks the release; upstream still waits 100 ms then `shutdownNow()`. Complementary, not a superset |
| `OpenGlView.kt` | R5, R11, R41, R54, R56 | Keep. Auto-merges |
| `Camera2ApiManager.kt` | R23, R25, R36, R37, R38, R41 | Keep. Auto-merges; upstream adds only new API and the template/customizer lines |

Every other item (R1 to R66) is in a file upstream did not touch.

## Merge resolutions

1. `AsyncBaseRecordController.start()`: take upstream (no job launch here, `startMuxerJob()` after
   `startRecordImp`). Keep R12's `bytesWritten = 0` / `rolloverNotified = false` in `start()`. They
   must stay before `startRecordImp`: the TS muxer counts its PSI bytes there.
2. `GlStreamInterface.stopLocked()`: keep R16's body and upstream's auto-merged
   `val executor = synchronized(sync) { ... }` line; drop `this.executor = null`. Extend the R16
   marker. Lock order stays gate -> `sync`; the two callbacks take only `sync`.

## Plan (each step after Andy's yes)

1. `git push origin master` (local `master` already equals `pedro/master`; fast-forward of 22).
2. In `.claude/worktrees/alpha1`, branch off `gpx-alpha1`, `git merge --no-ff pedro/master`, resolve
   as above, run the marker inventory and coverage checks.
3. `gradlew clean assembleDebug test` across all modules.
4. Same commit: R67 entry in the re-apply plan, policy file update, this file as
   `.claude/upstream-sync-2026-11-28-analysis.md`.
5. PR into `gpx-alpha1`, merged with a merge commit, never squash (a squash loses `pedro/master` as an
   ancestor). Check JitPack at the full SHA only when a pin move is wanted.
6. No tag, no pin move. Bench watch for the next pin move: WHIP connect (DTLS), record start and
   rollover while streaming, force-render on/off across GL stop/start.

## Skill and policy mismatches

- Skill: names `gpx-2.8`, its worktree, and a frozen `gpx-master`. The live branch is `gpx-alpha1` in
  `.claude/worktrees/alpha1`; both old branches are gone. Its preflight loop would fail. Its "0/0
  against origin" fails for `master` only because of the local-only pull. Its step 3
  `git fetch . pedro/master:master` is not needed and would be refused with `master` checked out.
- Policy file: the intro and "Rules" still say all GPX work goes on `gpx-2.8` with `2.8.0-gpx<N>`
  tags; its branch table says `gpx-alpha1`.
- Policy file: says the app pins `2.8.0-gpx3`. The app pins SHA `ed1366560`, and its runbook says SHA
  pin until a `beta1` tag.
- Policy file: "Verification status" narrative stops around R49.
- Suggested: fix both files in the R67 commit.
