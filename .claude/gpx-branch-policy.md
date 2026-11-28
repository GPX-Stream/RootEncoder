# GPX fork branch and tag policy

This fork carries one active GPX line, `gpx-alpha1`. GPX changes stay in
`GPX-Stream/RootEncoder`: never push to `pedroSG94/RootEncoder` or open a PR or issue there.
Treat the `pedro` remote as fetch-only.

## Branches

| Branch | Base | Purpose |
|---|---|---|
| `master` | — | Mirror of `pedroSG94/RootEncoder` master. Fast-forward only, never a GPX commit. |
| `gpx-alpha1` | upstream `9a9ca124f` (2026-07-30), synced through `5dcb864ca` (R67, 2026-11-28) | Active. The line `gpxstream-app` builds against. It is `gpx-2.8` (R46–R49) merged with `gpx/833-second-camera-rotation` (R50–R51) and upstream `f1bbda7ca` (R52, 2026-10-27). Both of those branches were deleted after the merge; `alpha1` stays a tag on the old R50–R51 tip. |

## Tags and who consumes them

The consumer, `gpxstream-app`, pins a **full commit SHA** of this branch, not a tag
(`ed1366560065c3b227d718ed21172a71e898c3b2` on 2026-11-28). The current value is in the app's
`gradle/libs.versions.toml`, and the pin rules are in its `docs/runbooks/rootencoder-fork.md`.
That runbook records Andy's ruling that the app pins SHAs until a `beta1` tag is cut, and that no
`2.8.0-gpx4` is cut.

| Tag | What it is |
|---|---|
| `alpha1` | `74227e260`, the R50–R51 tip. The app's runbook records it as the build that ran on the bench PDT. |
| `2.8.0-gpx1` to `2.8.0-gpx3` | Earlier pins of this line. |

JitPack builds each tag and each full SHA, so a pin resolves its commit regardless of what any
branch does afterwards.

## Retired lines

- **`gpx-master`** (base upstream `49421b686`, 2026-07-20) and **`teaky-frame-timing`**
  (`gpx-master` + one commit, `52926292d`, "encoded-frame timing callback for Teaky OBD/frame
  correlation") — both deleted 2026-09-12. `gpxnative-ai`, the only consumer of either (its
  `libs.versions.toml` pinned `52926292d` directly, `teaky-frame-timing`'s own tip, not a tag),
  is retired and no longer built. `gpx-master`'s own fixes were already captured elsewhere
  before deletion: the ones relevant to `gpx-2.8` were reapplied under their own R-numbers
  (R10, R32); the rest (a VBR-log copy-paste bug and an `OpenGlView` photo-size bug, both
  specific to the pre-`gpx-2.8` lineage) were never applicable to the active line. Deleting
  both branches leaves `52926292d` unreachable from any ref in this repo — recoverable only
  from `gpxnative-ai`'s own git history (or GitHub's reflog, for a limited window) if that
  exact build is ever needed again.
- **`2.7.5-gpx*`** (head `2.7.5-gpx25`) — the tag line `gpx-master` fed. No live consumer.
- **`gpx-2.8`** and **`gpx/833-second-camera-rotation`** — merged into `gpx-alpha1` (R52) and
  deleted 2026-10-27. Their history is in `gpx-alpha1`.

## Why the two lines cannot be swapped

Upstream changed `BaseEncoder.type` from a MIME `String` to a `com.pedro.common.Codec` enum
and deleted `getType()` and `setType()`. Any consumer that sets an encoder's codec compiles
against one form or the other, not both. Upstream also changed several `SdpBody` factory
signatures and moved parsers into `com.pedro.common`.

## Marking GPX changes in the source

**Every GPX edit carries a `GPX` marker comment at each place it changes.** One marker per
contiguous changed region — on the KDoc of an added member, or as the first line of a changed
block. The enumeration command is:

```
git grep -n "GPX " -- "*.kt" "*.java"
```

This exists because the alternative does not survive a rebase. A GPX change was previously
findable only from its commit message and this repo's re-apply checklist, and the next upstream
sync rewrites the commits and starts a new checklist — so at exactly the moment someone needs to
know "what did we change here, and why", the answer has moved out of the file.

**Two marker forms.** `GPX R<N>` where the change maps to a re-apply item
(`.claude/gpx-reapply-plan-2.8.0.md`), so a marker and a checklist entry name the same work; the
number is never reused across upstream syncs, and a re-applied change keeps the number it was
given. A follow-on to an existing item may carry a letter suffix (`R3b`) when the plan has a
checklist entry under that exact id; new work takes the next number. `GPX patch` where no item covers it — several small changes were made alongside a larger
item and were never enumerated separately. A bare `GPX patch` is preferred over guessing a number,
because a wrong attribution is worse than an absent one.

**Coverage.** On 2026-11-28, against `pedro/master` @ `5dcb864ca`, with R67 and R68 in the tree,
each of the 76 `.kt` and `.java` files that differed from it carried at least one marker:
`git diff --name-only pedro/master -- "*.kt" "*.java" | xargs grep -L "GPX"` printed nothing. A
sweep on 2026-10-27, against `f1bbda7ca` (R58), found no changed region without a marker within
14 lines above it. That sweep was not rerun for R67; a rough substitute gave the same count per
file before and after the R67 merge. The baseline is the *merged* upstream head, not the branch
point: a diff against the original `9a9ca124f` base also lists files only upstream changed. Two
caveats on reading a grep as complete:

- `StreamBase.kt` is a dense grouped rewrite; its class KDoc lists the groups, and each changed
  region carries its own marker.
- A marker is a comment, so a change with nothing to attach one to may be unmarked.

`git diff pedro/master -- <file>` remains the authority; the markers are what make the divergence
legible without running it.

## Rules

- **All GPX work goes into `gpx-alpha1`**, through a PR from a branch in its own worktree. Each
  GPX change needs its own reason and its own approval from the consumer side — "the fork is open
  anyway" is never one (the gpxstream-app fork-edit rule).
- **Built as R28 — per-encoder re-prepare (authorized at the gpxstream-app S8 gate, F2, Andy
  2026-08-12; shipped as `2.8.0-gpx2`).** `StreamBase.applyVideoStreamConfig` and
  `StreamBase.applyVideoRecConfig`: a scoped re-prepare that rebuilds one video encoder while
  the other encoder and the muxer keep running, stream side and record side each; honours the
  existing invariants (the record/stream aspect-ratio equality check, the record parameter
  set and its `recordCodecPrepared` claim discipline, B-frame suppression and the
  negotiated-format listener surviving a re-prepare). Device-proven on the consumer's bench
  PDT (gpxstream-app #78, 2026-08-14): a stream resolution change and a codec change ran with
  the recording rolling unbroken, and two H265 encoders allocated concurrently — R-STR-14's
  named risk, cleared. Design home: `gpxstream-app/docs/design/S8_recording_vod.md`
  (F2), build placement in its implementation plan (WP9).
- **Never reuse or move a published tag.** JitPack caches builds per tag; a moved tag serves
  stale or mismatched artifacts.
- **JitPack's install command excludes `:app:assemble`** (`jitpack.yml`). JitPack's default
  Android build runs root-level `assemble`/`publishToMavenLocal`, which builds every module in
  `settings.gradle` including the sample `:app` module — the only one with no `maven-publish`
  plugin and nothing JitPack ever serves to a consumer. Building it anyway forces
  `:app:mergeDexDebug` to dex the demo app's full dependency graph (CameraX, media3-exoplayer,
  UVCAndroid, ktor, BouncyCastle), which reliably exhausts JitPack's build-container disk quota
  (D8 fails with `java.io.IOException: No space left on device` — easy to misread as a
  BouncyCastle/D8 incompatibility from the build log's summary line alone). Root-caused and
  fixed 2026-09-02: a prior check of this had wrongly concluded "no fix needed" because it
  tested JitPack against the *short* commit hash, which JitPack treats as an entirely separate
  cached build from the full 40-character SHA gpxstream-app actually pins — always verify
  against the full SHA.
- **Upstream syncs** follow `.claude/skills/upstream-sync/SKILL.md`. A sync PR is merged with a
  merge commit or a fast-forward, never squashed or rebased, so `pedro/master` stays an ancestor.
  The item-by-item record is in `.claude/gpx-reapply-plan-2.8.0.md`.

## Verification status

`gradlew assembleDebug test` is recorded as passing across every module and the sample app at
each `2.8.0-gpx*` tag. For later heads, read the paragraphs below.

**`2.8.0-gpx1` — device-proven over WHIP (2026-08-02).** On a bench PDT-FP1 the WHIP path
reached `Streaming` against the Millicast ingest and video was watched end to end on the
dashboard for the whole test. The stream held across a screen lock for over a minute with the
camera retained, and switching between all three inputs was exercised on the same line. Not
covered: duration, a degrading link, and a moving vehicle — nothing has run for hours or in
adverse conditions.

**`2.8.0-gpx2` (R23, R25, R26, R27, R28) — shipped 2026-08-12 with the consumer's pin move
(gpxstream-app #46), superseding R24's tag hold.** On the bench PDT the consumer's #78 pass
(2026-08-14) exercised R28 directly: a stream resolution change and a codec change ran with the
recording rolling unbroken, and two H265 encoders allocated concurrently. The item-specific
provocations R24's bench checklist named for R23/R25/R26/R27 have no recorded pass and stay open
as watch items — the ordinary paths they sit on have run on the bench since without incident.

**`2.8.0-gpx3` (R29, R30) — shipped; concurrency coverage is build-verified.** Both
changes rode the consumer's #78 bench build. Their specific interleavings (a wedged muxer write,
a stopRecord racing a stopStream) are not reproducible on a bench, so device proof is of the
unbroken ordinary paths, not the races. The app has since moved to SHA pins (see "Tags and who consumes them").

**R31–R35, untagged after `2.8.0-gpx3` — build-verified when they landed.** The range carries R31, the
merge of upstream `pedro/master` @ `300d99fe1` (surface-PTS rebase onto the shared start, the
GL timestamp rework, a timestamp-based fps limiter — all on the live video path, so the next
consumer pin move wants a bench watch for A/V sync and frame pacing), R32, the encoded-frame
timing seam ported from `teaky-frame-timing` (inert until a listener is set), R33, the
swappable network-transport seam (`library/src/main/java/com/pedro/library/transport/`:
`StreamTransport`, `GenericTransport`, `WhipTransport`, `SwitchableStream`, `TransportPrimer`) —
consumer issue #110, so a protocol change no longer forces a full rebuild of the recording path —
and R34, a synchronized GL-init wait in `GlStreamInterface.start()` (consumer issue #108) so a
timeout or exception there throws instead of being silently swallowed. Three further
fork changes rode with them: R54, `setRecordOverlay` — a
second `StreamOverlayRender` instance drawn into the record encoder target only, giving the
stream and the recording independent overlay composites; R55, burning that same
record-target overlay into photo captures too, so a VOD thumbnail always matches the recording it
is a thumbnail of; and R56, `GlInterface.setPreviewFrameListener` (consumer issue
#215) — a no-op-unless-set, exception-isolated callback fired once per GL frame actually drawn
into the attached preview target, independent of streaming/recording state, so the consumer can
build a local-preview liveness watchdog without a second listener on the camera's own
`SurfaceTexture` (which the fork already claims exclusively for its own render loop). Zero edits
to `StreamBase.kt` from any of R33/R34/R54/R55/R56. `gradlew assembleDebug test` passes across
every module and the sample app with all of the above in the tree. On top of this, R35 merges
upstream `pedro/master` @ `1285703b` (a new congestion-aware `QueueAwareBitrateAdapter` utility
not wired into anything GPX touches, a Camera2 metering-region-clear correctness fix, a
`BitrateManager.reset()` timestamp fix, and a `StreamBase.stopSourcesImp()` teardown reorder —
`glInterface.stop()` now runs after the three encoder `.stop()` calls, the one edit to
`StreamBase.kt` in this range, auto-merged cleanly under R30's synchronized-teardown wrapper
with no conflict). A consumer pin move onto this head therefore also promotes the 27
previously-unbenched R31/R32 commits at the same time, so the next bench gate consciously
widens to cover R31/R32's A/V-sync and frame-pacing watch items alongside R33's live protocol
swaps, R34's preview-attach fix, R54/R55/R56's own composite/liveness behavior, and
R35's teardown-order change (ordinary stop/release paths, not a new invariant — low watch
priority, but new to this range).

**R39, R40 (2026-09-13) — two upstream commits cherry-picked ahead of the next full sync, not
part of one.** `master` was fast-forwarded to `pedro/master` @ `620d05ffb` (8 commits past R35);
of the 4 non-merge commits in that range, 2 were adopted as targeted teardown-robustness fixes
with no GPX-marked lines nearby (R39: `BaseSender.stop()` no longer hangs a `reConnect`
indefinitely when the sender is blocked in a TCP write under backpressure; R40: an `isActive`
early-exit in the muxer-drain loop, plus a fire-and-forget swap for the bitrate/stats callback
dispatch that closes a real deadlock shape between `stopRecord()`'s `runBlocking` and a
suspended `onMainThread` call). One commit (SRT server-silence-timeout) was evaluated and kept
as a documented "ours is better, no change" against `GPX R7`; one (`isRecording`→`isRunning` in
the legacy `Camera1Base`/`Camera2Base`/`DisplayBase`/`FromFileBase`/`OnlyAudioBase` classes) is
not applicable — this consumer builds exclusively on `StreamBase`, which already had the
equivalent fix. Full comparison in `.claude/upstream-sync-2026-09-13-analysis.md`. No tag cut,
no pin move — ordinary stop/teardown paths, low watch priority, folded into whatever bench gate
the next tag/pin move covers.

**R41 — a second, independent camera source (consumer issue #272, Decision 4).** Built directly,
without waiting on a prerequisite bandwidth/thermal bench test — the consumer's ruling
(2026-09-13) drops that gate for this work; ordinary bench QA applies once it exists, same as any
other slice. Two parts:

- **A second concurrent camera-open path needs no new code in `Camera2ApiManager`.** Every piece
  of its open/close state (`openGeneration`, `captureSessionExecutor`, `cameraDevice`, and so on)
  was already a per-instance field, not shared — so a consumer holds two cameras open at once
  simply by constructing two independent instances, each with its own bounded open, its own
  generation guard and its own reused executor (fork changes R23/R36/R37/R38), for free. What *is*
  new: `Camera2ApiManager.canOpenConcurrently(context, cameraId, otherCameraId)`, backed by
  `CameraManager.getConcurrentCameraIds()` (API 30+), because whether two sessions can actually run
  concurrently is a real per-device HAL constraint no fork change can assume — it returns false
  below API 30 or when the platform reports no matching combination, and the caller is expected to
  treat that the same as any other camera the device refuses.
- **A second GL input pipeline, added to `MainRender`/`GlStreamInterface`.** `GlInterface` gains
  `getSecondarySurfaceTexture()`/`getSecondarySurface()` (attach a second open camera to these, the
  same way the existing ones attach the first) and `setStreamSource(GlCameraSource)`/
  `setRecordSource(GlCameraSource)` — which of the two open sources each render target draws its
  base picture from, mirroring R54's `setStreamOverlay`/`setRecordOverlay` per-target
  shape. The second pipeline is fully lazy: nothing is allocated until a caller actually asks for
  the secondary SurfaceTexture, so a consumer that never touches this is unaffected — same texture,
  same draw calls, same cost as before this existed. Switching a target's source is a GL-side
  texture rebind inside `GlStreamInterface.draw()` — it never touches `prepareVideo`, an encoder,
  or the muxer, the bar the consumer's own text sets ("the old code already swaps the source under
  a running encoder without cutting the file"). `OpenGlView` (no second render pipeline to bring
  up) throws on the two getters and no-ops on the two setters. Preview, multi-preview and photo
  capture are unchanged — Decision 4 names only the stream and record targets, so those three keep
  drawing the primary/filtered pipeline unconditionally regardless of what `setRecordSource` is set
  to; a VOD thumbnail can therefore show a different camera than what `setRecordSource` just routed
  into the recording file, a known, deliberately out-of-scope asymmetry with R54/R55's
  overlay-follows-record precedent.

`gradlew clean assembleDebug test` passes across every module and the sample app. Not
bench-verified — no device access from this session. Two things the next bench pass needs to
check, named explicitly because nothing here can confirm them: whether the fleet PDT's camera HAL
actually reports a concurrent-capable id combination via `getConcurrentCameraIds()`, and whether
the record target's base picture genuinely shows the second camera's own feed, independent of
whatever the stream target is drawing, when both are opened together.

**R42 — a WHIP inbound-silence signal (consumer issue #240).** `WhipStreamClient.getInboundSilenceMs()`
now reports milliseconds since the WHIP server last sent a media-plane (RTCP) packet, instead of
the -1 stub: R14's write-only `mediaPlaneIn` counter gains a last-arrival time
(`whip/src/main/java/com/pedro/whip/utils/InboundSilenceTracker.kt`). Read-only exposure — no
fork-side watchdog acts on it; the consumer judges it. `gradlew clean assembleDebug test` passes
across every module and the sample app, including the new `InboundSilenceTest`. Not
bench-verified: the next bench pass needs the Millicast ingest's actual RTCP cadence on a live WHIP
publish, and a silence reported when the ingest stops receiving.

**R43, R44 (2026-09-22): two more upstream commits cherry-picked ahead of the next full sync.**
Both come from `pedro/master` past `620d05ffb`, and both applied with no conflicts and no GPX-marked
line in any hunk.

- **R43 (`54b196108`):** `AudioEncoder` stops forwarding the MediaCodec codec-config buffer as an
  encoded audio frame. Previously it went out as a bogus audio unit at every stream start, on
  every protocol, and could land in a recording on a cold start or an audio encoder restart.
- **R44 (`562973772`):** SRT retransmits are capped by a token bucket (default 25% of the
  estimated media rate, 0.5 s burst). Packets go oldest-first, and packets that would arrive after
  their latency, or repeat a recent retransmit, are skipped. This stops a receiver's repeated NAK
  reports from amplifying loss on a bottlenecked uplink.

Full comparison, including the libsrt default comparison and whether each reaches the consumer, in
`.claude/upstream-sync-2026-09-22-analysis.md`. `gradlew clean assembleDebug test` passes across
every module and the sample app. No tag cut, no pin move. Not bench-verified. The next bench pass
should:

- play a clip cut from the start of a cold-started recording in Chrome;
- stream SRT under induced loss or a throttled uplink and watch for frame drops and recovery
  after a short outage.

**R45 (2026-09-29) — GL start and video-source start are serialized (consumer flight-recorder
audit, device 74762).** A preview start on Main and a record start on another thread could both call
`GlStreamInterface.start()` while its init was still running; the second call tore down the first's
init, giving `GL init failed` and a dead preview. `GlStartGate` now makes `start()`/`stop()` mutually
exclusive and `start()` idempotent while running, and `StreamBase.startVideoSourceIfNeeded()`
serializes the camera check-plus-start. Lock order is `lifecycleLock` → `videoSourceStartLock`, never
the reverse; full record in `.claude/gpx-reapply-plan-2.8.0.md`. `gradlew clean assembleDebug test`
result is in the PR. Only the gate's serialization logic has a unit test; the rest is code reading
plus the bench. The race is intermittent, so a bench run is a regression pass, not proof. No tag cut,
no pin move by this change.

**R46–R49 (2026-09-30) — SRT timeout, clock and WHIP failure-handling fixes (consumer issues
#337, #338, #341).**
R46: `SrtClient` derived its socket timeout from latency in the wrong unit (`latency / 1000` on a value
already in milliseconds), which held the whole SRT handshake to about one second at any latency and
kept R8's re-knock backoff from ever engaging; it is now latency plus one second
(`socketTimeoutMsFor`). R47: the WHIP receive loop ended silently on any exception, which R42 then read
as a silent ingest; it now reports a real fault once through `onConnectionFailed`, survives a read
timeout, and ends quietly on cancellation or when `disconnect()` closed the socket (`ReceiveLoop`).
R48: the SRT handshake budget never drops below 4000 ms, derived from R8's schedule, so R8's knock
after a full capped gap always goes out. R49: R7's silence timer and R8's handshake deadline read
elapsed realtime instead of the wall clock, so a clock step cannot fake or hide a dead link.
Full records in `.claude/gpx-reapply-plan-2.8.0.md`. `gradlew clean assembleDebug test` result is in
the PR. Unit-tested as pure functions only; `SrtClient.connect` and `WhipClient.connect` need a live
peer and are not exercised off-device. Not bench-verified. No tag cut, no pin move by this change.

**R50–R66.** Per-item records are in `.claude/gpx-reapply-plan-2.8.0.md`. Bench status is tracked
in the app's `docs/runbooks/rootencoder-fork.md` and `gpxstream-app` issue #336.

**R67 (2026-11-28): upstream merge through `5dcb864ca`.**
`gradlew clean assembleDebug test --continue` on the Windows build machine, with R68 merged in,
passed: 490 tests, 0 failures, 0 skipped. That includes the WHIP tests that run a DTLS handshake
against a fake ingest on BouncyCastle 1.86. Not bench-verified. The next app pin move should watch,
on the bench:

- a live WHIP connect (the BouncyCastle 1.86 DTLS handshake);
- a record start and a rollover while streaming;
- force-render switched on and off across a GL stop and start.

**R68 (2026-11-28): the Ktor UDP socket reports a numeric sender address**, which is the text
R53's source filter compares. The record, with its build result and bench watch item, is in
`.claude/gpx-reapply-plan-2.8.0.md`. Not bench-verified.
