# Live VIP — Part 4: Critical Streaming Stability Patch

> Scope: FIX ONLY. No engine rewrite, no UI redesign, no feature removal.
> All Part 1–3 behavior preserved; 100% of existing unit tests still pass.

## 1. Video-end / loop pipeline (spec §1, §2, §15)

Root causes found and fixed:

| # | Root cause | Fix |
|---|---|---|
| 1 | `onPlaylistBoundary` updated `loopCount` only AFTER a successful source switch — a failed switch reported **Loop #0** while the stream ran through a file | Counter reflects the completed boundary IMMEDIATELY (`onItemFinished()` increments first) |
| 2 | `restartVideoSource()` (watchdog recovery) called `replaceFile()` WITHOUT re-applying the loop flag — the fresh decoder defaults to loop-off, so a recovered single-video stream EOS'd into a boundary instead of looping | `setLoopMode(usesInternalLoop)` re-applied on every recovery path (video + audio) |
| 3 | Video/audio decoders loop INDEPENDENTLY at their own EOF — when the audio track length ≠ video track length, content positions misalign by that difference after every loop (permanent lip-sync error, the "A/V Sync !" case) | `AudioLoopSync` (pure, tested): at every video loop boundary the audio decoder's file position is checked; if it is not near the loop start it is restarted from 0 (transition executor, never main/decoder thread). Encoder/muxer/RTMP untouched |

Timeline continuity (already true, now verified against RootEncoder 2.8.1 source):
decoder timestamps are wall-clock based (`now - startTs`) with an accumulating
extractor delta — a loop `seekTo(0)` never resets the output timeline. Loop N
continues at N×duration, exactly as specified.

## 2. False LIVE / YouTube ingestion (spec §8, §10)

- New engine state `PUBLISHING`: socket connected + handshake OK, media flow
  not yet verified. UI shows **STREAMING TO SERVER** (amber), LIVE badge hidden.
- `IngestVerifier` (pure, unit tested): LIVE only after
  1. RTMP/RTMPS connected, 2. handshake OK, 3. video packets flowing,
  4. audio packets flowing, 5. flow sustained 6 s, 6. no fatal condition.
- Honest failures with real reasons:
  - "Connected to RTMP(S), but video packets are not being accepted…" (20 s, 0 video frames)
  - "Publishing started, but no valid audio was detected by the server." (audio stuck)
  - "Ingest could not be verified — media flow was inconsistent." (25 s timeout)
- No platform API/OAuth is available in this build → per spec the state is
  labeled **STREAMING TO SERVER** until ingest is verified, never fake LIVE.

## 3. A/V sync diagnostics (spec §6)

`StreamStats.avSyncMs` now carries the measured drift (encoded video timeline
coverage − audio coverage, from `TimelineGuard`). Dashboard shows the real
number: `±12ms` green (<80), amber (<250), red (≥250). No fake "!" badge.

## 4. Zoom/crop after START LIVE (spec §3, §4, §16)

Root cause: the home dashboard preview `SurfaceView` was `match_parent` inside
a fixed-height container — a 9:16 encoded frame drawn there appeared
stretched/zoomed although the ENCODED output was correct.

Fix: the dashboard preview surface is sized to the ACTIVE canvas aspect via
`CanvasPreviewMath` (layout-change driven, same math as the canvas editor).
One composition, one truth: GL filter chain → encoder surface AND preview
surface; the preview merely now has the correct aspect.

## 5. Component health dashboard (spec §17)

Real probes, displayed every second:
`Decoder HEALTHY • Encoder HEALTHY • Muxer HEALTHY • RTMPS CONNECTED • Ingest VERIFIED`
- Decoder: file-source time advancing (camera = always healthy)
- Encoder: sent-video-frame delta > 0
- Muxer: video or audio frame delta > 0
- RTMPS: socket state / relay mode
- Ingest: VERIFIED (LIVE + frames) / VERIFYING (PUBLISHING)

## 6. Already-solid parts re-verified (no change needed)

- **Reconnect safety** (spec §12): bounded exponential backoff 1s→2s→…→30s cap,
  attempt counter shown ("Reconnecting… attempt N of M").
- **Targeted watchdog recovery** (spec §11): decoder-only / audio-only /
  keyframe-request actions (Part 1 `WatchdogCenter`) — kept, plus the
  loop-mode fix above.
- **App reopen** (spec §13): streaming lives in the foreground service; Home
  restores the live dashboard (duration, loops, destinations, state) and
  `startBroadcast` refuses double-starts ("Already streaming").
- **Keyframes** (spec §9): 2 s default, ≤4 s.

## 7. Tests added

- `IngestVerifierTest` (9): sustained-flow verify, honest zero-video /
  zero-audio failures, disabled-audio waiver, timeout ordering, stale-window
  rejection.
- `AudioLoopSyncTest` (3): aligned window, mid-file resync, invalid time safe.

## 8. Honest limitations

- Soak tests (30 min–12 h) require a physical device + real RTMP server; not
  executable in CI. The loop/A/V-sync logic is unit tested and the failure
  paths that produced "Loop #0" are structurally closed, but the 6 h+ runs
  remain device tests for the user.
- Platform-side LIVE confirmation (YouTube API) is not integrated; per spec
  the app labels the pre-verification state STREAMING TO SERVER instead of
  claiming LIVE.
