# Upstream sync 2026-09-22 — overlap analysis

Two upstream `pedroSG94/RootEncoder` commits past the 09-13 batch (`620d05ffb`), evaluated for
adoption onto `gpx-2.8` ahead of the next full sync, the same way R39/R40 were
(`.claude/upstream-sync-2026-09-13-analysis.md`). `pedro/master` carries 4 commits past
`620d05ffb`: these two, plus their two PR merge commits (`d6c9ced44`, `ebb9b4b0c`). The `master`
mirror was not fast-forwarded in this pass. Adoption was approved by the consumer's owner
(Andy, 2026-09-22).

## 1. Audio codec-config buffer forwarded as a frame — `54b196108`

**Upstream (`54b196108`, "audio: do not forward the codec config buffer as an encoded frame"):**
`AudioEncoder.checkBuffer` returns false for a buffer flagged `BUFFER_FLAG_CODEC_CONFIG`, ahead of
the existing `checkValidTimeStamp` call. MediaCodec emits the codec config (for AAC, the 2-byte
AudioSpecificConfig) as its first output buffer; `checkBuffer` only checked the timestamp, so
that buffer went downstream as an ordinary encoded frame. Upstream's report: senders transmit it as
a bogus 2-byte AAC frame, and `AndroidMuxerRecordController` writes it as the first audio sample
of an MP4, which Chrome refuses to play (`PIPELINE_ERROR_DECODE`) while FFmpeg and VLC skip it.
Adds `encoder/src/test/java/com/pedro/encoder/AudioEncoderCheckBufferTest.kt`.

**GPX markers on or near the touched code: none in the changed method.** `AudioEncoder.java`
carries one marker, `GPX R9` in `start()` (continuous timestamps across a stop/start), not in
`checkBuffer`. Nothing else GPX sits on the path from `checkBuffer` to the packetizers.

**Interaction with GPX changes.**

- **R9:** no conflict. R9 governs `tsBuffer` in `start()`. The new check returns *before*
  `checkValidTimeStamp`, so the config buffer no longer seeds `BaseEncoder.oldTimeStamp`.
  Before the fix, a config buffer whose PTS was ahead of the first real frame's would have caused
  that frame to be rejected as non-monotonic. That can no longer happen — a small correctness gain.
- **R32:** `StreamBase.getVideoData`'s frame-timing observer already skips `CODEC_CONFIG` buffers
  on the video side, and `AacMuxerRecordController` skips them for its AAC-only file. Filtering at
  the encoder is the right single interception point for audio, instead of a filter in every
  consumer.

**Does the bug reach gpxstream-app?** Traced in this tree: `AudioEncoder.checkBuffer` →
`sendBuffer` → `StreamBase.getAacData.getAudioData`, which calls both
`transport.sendAudio` (via `SwitchableStream.getAudioDataImp`) and `recordController.recordAudio`.
`MediaFrame.Info` keeps the MediaCodec `flags` (`toMediaFrameInfo`), but no audio consumer on the
app's paths checks them. `git grep CODEC_CONFIG` finds only the two sites above. Audio is on by
default (`AudioGroup.enabled = true` in the app's `SettingsBlob.kt`; `StreamBaseFactory` then
installs a `MicrophoneSource`), so recordings use `RecordTracks.ALL`.

- **Stream side: yes, once per audio encoder start, on every protocol.** The config buffer is the
  encoder's first output:
  - RTMP sends it as a 2-byte RAW AAC FLV tag right after the sequence header.
  - SRT wraps it in a 7-byte ADTS header and sends it as a 9-byte audio PES.
  - WHIP forwards it as an RTP Opus payload. This assumes the device's Opus encoder emits its
    OpusHead-based config the same way; Android's framework encoders deliver config as a
    `CODEC_CONFIG` output buffer, but this is not confirmed on the PDT.

  What Millicast or its viewers do with that one malformed unit per encoder start has not been
  observed.
- **Recording side (`Mpeg2TsMuxerRecordController`, which the app's `RecordCapture` installs):
  reachable, but only on some paths, and not reproduced.** The TS controller writes audio only
  once it is `RECORDING` (`onWriteFrame`). Three cases:
  - *Recording started while the encoders are already running* (for example, streaming first):
    **never reached.** The config buffer went out at encoder start, long before the controller
    existed.
  - *Recording that brings the sources up itself* (`StreamBase.startRecord` puts the controller in
    `STARTED`, then calls `startSources()`): **a race.** Frames reach the file asynchronously on
    the muxer coroutine, and what counts is the controller's status when that coroutine processes
    the audio config frame. If the frame is processed while still `STARTED`, it is dropped. If the
    video side has already moved the controller to `RECORDING` (through `setVideoFormat` with
    SPS/PPS, or the first keyframe), it is written.
  - *Audio encoder restart while a recording is rolling* (`BaseEncoder.reloadCodec` → `reset()`
    on a recoverable `CodecException`): **deterministic.** A fresh config buffer lands in a
    `RECORDING` file mid-stream. This is a rare path.

  When it lands, the AAC case is a 9-byte ADTS frame whose 2-byte payload is the
  AudioSpecificConfig. Media3's TS extractor reads ADTS frames, so a clip transmuxed from that
  point (R-VOD-09, the app's `ClipTransmuxer`) would carry a 2-byte audio sample in its MP4. That
  is the shape upstream reports breaking Chrome, but only when the clip's range covers that point.
  WHIP-mode recordings carry Opus-in-TS (the record audio codec follows the protocol, the app's
  `EncoderConfigurator`). There the config blob would land as an Opus-in-TS access unit; how
  Media3 handles Opus-in-TS is a separate question, not examined here.

**Is dropping the buffer safe on every path the app uses? Yes. Each path builds its audio config
independently, and none reads the config buffer:**

- **RTMP:** `rtmp/.../flv/audio/packet/AacPacket.kt` sends the FLV AAC sequence header once
  (`configSend`). It builds it with `AacAudioSpecificConfig(objectType, sampleRate, channels)` from
  the values `setAudioInfo` supplied. With the config buffer dropped, the sequence header rides the
  first real frame instead.
- **SRT:** `SrtSender.setAudioInfo` → `srt/.../mpeg2ts/packets/AacPacket.sendAudioInfo`. Every
  frame gets its own ADTS header from `AudioUtils.createAdtsHeader(type, length, sampleRate,
  channels)`. The Opus-in-TS packet (`OpusPacket`) adds only a control header.
- **WHIP:** the SDP comes from `SdpBody.createOpusBody(track, sampleRate, isStereo)`. RTP Opus
  (`rtsp/.../rtp/packets/OpusPacket.kt`) carries no in-band config.
- **TS recording:** `Mpeg2TsMuxerRecordController.setAudioFormat` reads `KEY_SAMPLE_RATE` and
  `KEY_CHANNEL_COUNT` from the `MediaFormat`, not from any buffer, and passes them to the same
  `AacPacket.sendAudioInfo`.
- **Not used by the app:** `AndroidMuxerRecordController` takes `csd-0` from the `MediaFormat`, and
  RTSP signals config in the SDP. Neither needs the buffer either.

**Recommendation: adopt as-is.** Clean cherry-pick with no GPX lines involved. It fixes a malformed
audio unit on every stream start and removes one route to a Chrome-unplayable clip.

## 2. SRT NAK-driven retransmit storm — `562973772`

**Upstream (`562973772`, "srt: limit retransmissions to avoid a NAK-driven retransmit storm"):**
Some receivers re-report every still-missing packet on each NAK interval (20 ms minimum).
`reSendPackets` resent every reported packet unconditionally, so on a bottlenecked link one lost
packet could be resent up to `latency / 20 ms` times, and loss fed on itself. The change:

- **`CommandsManager.kt`:**
  - A token bucket limits retransmit bandwidth to `retransmitOverheadPercent` (default 25) of an
    EWMA-estimated media rate, with a floor of 8 kB/s. Its burst capacity is the larger of 0.5 s
    of media and `rate × latency`, never below one MTU.
  - Packets are resent oldest-first. Once the budget runs out, the rest of that NAK is skipped.
  - A packet that cannot arrive before its latency expires is skipped:
    `nowTs - packet.ts + rtt/2 >= latency`.
  - An already-retransmitted packet reported again within `max(rtt + 4·rttVariance, 20 ms)`,
    capped at `latency/4`, is skipped.
  - `reSendPackets` returns the number of newly reported sequences.
  - `<= 0` restores the old unlimited behavior.
- **`DataPacket.kt`:** gains `lastSentTs` and `nakReported`.
- **`SrtClient.kt`:** gains `packetsLostUnique`, `setRetransmitOverhead(percent)`, and a
  `commandsManager.updateRtt(rtt, rttVariance)` call on every ACK.
- **`SrtStreamClient.kt`:** gains `getPacketsLostUnique()` and `setRetransmitOverhead(percent)`.
- **Test:** adds `srt/src/test/java/com/pedro/srt/srt/CommandsManagerTest.kt`.

**GPX markers on or near the touched code: none inside any hunk.** `CommandsManager.kt` and
`DataPacket.kt` were identical to upstream's parent of this commit, with no GPX changes.
`SrtClient.kt` carries `GPX R7` (inbound-silence watchdog), `GPX R8` (handshake retransmit) and two
`GPX patch` regions (full-path streamid, `socketTimeout` re-derivation). None of them overlaps the
five upstream hunks (property block, `setRetransmitOverhead` beside `setLatency`, the
`disconnect` counter reset, and the ACK and NAK branches of `handleMessages`), and git
auto-merged them. `SrtStreamClient.kt`'s one marker (`GPX R7`, the `getInboundSilenceMs`
override) sits mid-file; upstream appends at the end. The `setServerSilenceTimeout` API declined
on 09-13 did not come back — this commit does not touch it.

**Interaction with GPX changes.**

- **R7:** no conflict. R7 stamps `lastInboundMs` at the top of `handleMessages`, before any branch
  runs, and reads nothing outbound. The cap changes only what the NAK branch writes.
  Retransmits run inline in the same coroutine that reads packets
  (`handleServerPackets` → `handleMessages` → `reSendPackets`). A capped pass returns sooner, so
  the gap between reads can only shrink. `updateRtt` briefly takes `writeSync` on each ACK, after
  `lastInboundMs` is already set.
- **R8:** not touched. The new state is cleared by `commandsManager.reset()` on disconnect, and
  the bucket initializes lazily on the first NAK after a reconnect.

**Interaction with the app's SRT levers.**

- **`srtLatency`** travels on the connect URL (`latency=<ms>`, the app's `MillicastUrlBuilder`) into
  `commandsManager.latency`. That value is in milliseconds (`SrtStreamClient.setLatency`'s KDoc,
  and the 16-bit TSBPD delay field in the handshake), which is the unit upstream's new code
  assumes. Latency now shapes the cap in three places:
  - the expiry skip;
  - the `latency/4` ceiling on the resend gate;
  - the `rate × latency` floor on burst capacity.

  At the app's default of 2000 ms and 25%, burst capacity works out to 0.5 s of media either way.
  Higher latency gives a larger burst and a more lenient expiry. That is a real coupling, not a
  conflict. The app's range (0..30000 ms) keeps `latency × 1000` inside `Int`. At latency 0 the
  capped mode skips every retransmit. That changes nothing in practice: `dropTooLatePackets` with a
  0 µs threshold already empties the retransmit queue.
- **`sendCacheFrames`** sizes `SrtSender`'s frame queue ahead of packetization (`resizeCache`), a
  different queue from the retransmit buffer (`packetHandlingQueue`), so there is no direct
  interaction. Indirectly, retransmits and new data share `writeSync` and the socket. An uncapped
  storm takes send time away from new frames, which is what fills that cache and drops frames. The
  cap limits that.
- The app does not read `getPacketsLost()`. Its `LivenessSampler` reads `getInboundSilenceMs()`,
  which this commit leaves unchanged.

**Is 25% right for Millicast's ingest over cellular?**

- **Not the same as libsrt's default, despite the commit message.** Per libsrt's socket-option
  docs, `SRTO_OHEADBW` (default 25) takes effect only when `SRTO_MAXBW` is 0, and `SRTO_MAXBW`
  defaults to -1, which means no cap. Even when active, libsrt's 25% bounds *total* send
  (`INPUTBW × 1.25`), not retransmits alone. So this default is stricter than an out-of-box libsrt
  sender. The 0.5 s burst allowance softens that for short loss events.
- **The storm is reachable against a libsrt receiver.** `SRTO_NAKREPORT` defaults to true in live
  mode: a loss report is repeated whenever its expected-retransmission timeout passes without
  recovery. It has not been confirmed whether Millicast's SRT ingest is libsrt-based, but periodic
  re-reporting is the norm either way. libsrt's own sender defaults to
  `SRTO_RETRANSMITALGO = 1`, which sends fewer retransmissions per lost packet; upstream's resend
  gate is the equivalent here.
- **Judgment: a sound default, not bench-measured.** Random loss at ordinary cellular rates (low
  single-digit percent) sits well inside a 25% budget. The budget binds only under sustained heavy
  loss or a long outage on a bottlenecked uplink. That is where uncapped resending feeds on itself.
  The trade-off: in that regime some packets are skipped (the receiver drops them at TSBPD expiry)
  instead of being retried at the cost of starving new data.
- **Tunability: not reachable from the app today (note only).** `setRetransmitOverhead` exists
  on `SrtStreamClient` and `SrtClient`, but not on `GenericStreamClient` or `StreamBaseClient`.
  The app's `SwitchableStream.getStreamClient()` returns `GenericStreamClient`, which holds its
  `SrtStreamClient` privately. Tuning later needs a passthrough, the same shape as R7's
  `getInboundSilenceMs` override — its own fork change, with its own approval. The value lives on
  `CommandsManager` and survives a reconnect (`reset()` leaves it alone). A transport swap that
  builds a new `GenericTransport` (R33) starts again from the default, so a wired lever would need
  re-applying after a swap.

**Recommendation: adopt as-is.**

**Noticed in passing, not changed here.** The `GPX patch` in `SrtClient.connect` computes
`socketTimeout = (commandsManager.latency / 1000L) + 1000L`, commented "latency is microseconds".
Latency is in milliseconds (above), so this yields about 1000 ms plus `latency/1000` (1002 ms at
the app's 2000 ms default), not `latency + 1000` ms. That value is also R8's total handshake knock
budget and the read loop's socket timeout. The 09-13 analysis's description of `socketTimeout`
("several seconds at a higher configured latency") shares the same reading. This predates and is
unrelated to R43/R44; it needs its own decision.

## Implemented

Cherry-picked with `-x` onto `feat/adopt-upstream-audio-csd-srt-retransmit`, in order:
`54b196108` (R43), then `562973772` (R44). The branch started as a worktree off `gpx-2.8` @
`ffba4217d` and was rebased onto `f5b2a2315` (R42, merged meanwhile) before its PR. Both
cherry-picks applied with no conflicts, and `AudioEncoder.java`, `SrtClient.kt` and
`SrtStreamClient.kt` auto-merged. R42 touches none of R43/R44's source files. The rebase
conflicted only in this repo's two docs, where both branches appended after R41; both sides were
kept, R42 first. The GPX marker inventory (`git grep -n "GPX" -- "*.kt" "*.java"`, line numbers
stripped) is identical before and after, against both bases: 216 lines at `ffba4217d`, 225 at
`f5b2a2315`. No marker was dropped or added. No inline `GPX R43`/`GPX R44` markers,
following the convention for adopted upstream code (R39/R40); both are tracked in
`.claude/gpx-reapply-plan-2.8.0.md`. `gradlew clean assembleDebug test` passes across every module
and the sample app. No tag cut and no pin move, per standing policy.

**Bench watch items for the next consumer pin move** (build-verified only; nothing here was
device-tested):

- **R43:** a clip cut from the very start of a cold-started recording, and one spanning an audio
  encoder restart, play in Chrome. Also: the first seconds of an RTMP, SRT and WHIP stream on
  Millicast carry clean audio.
- **R44:** SRT under induced loss or a throttled uplink. Compare `packetsLost` against
  `packetsLostUnique` (both readable only inside the fork today), watch for visible frame drops,
  and check that the stream recovers after a short outage without a retransmit storm.
