# Live VIP — PART 1 Architecture (Broadcasting Studio Upgrade)

## Existing engine (PRESERVED — verified in RootEncoder 2.8.1 sources)

```
CAMERA LIVE ──┐
              │  StreamBase (RootEncoder 2.8.1)
VIDEO LIVE ───┤    ├─ VideoFileSource   (HW decoder → GL SurfaceTexture, internal gapless loop)
              │    ├─ MixedFileAudioSrc (video PCM + independent mic PCM → mix → AAC)
              │    ├─ GlStreamInterface (MainRender + filter chain → encoder surface)
              │    ├─ H.264 / AAC HW encoders (started ONCE per session)
              │    └─ RTMP/RTMPS client(s)
              └─ LiveStreamingManager (singleton, owns engine lifetime)
                     ▲
                     │ listeners only — UI NEVER owns the engine
                LiveStreamingService (foreground service, notification, STOP action)
```

### Why looping already works and must keep working
* `GlTimestamp.getTimestamp()` (RootEncoder): when the decoder restarts its timestamps at 0
  (loop boundary) the GL pipeline substitutes the **wall-clock delta** — output timestamps
  continue monotonically. The encoder is never restarted, so output PTS never regresses.
* `AudioEncoder.calculatePts()` (RootEncoder): AAC PTS is derived from the **sample count**
  (`tsBuffer`), reset only when the encoder starts — never on loop / file change.
* Therefore: loop = decoder-internal seek. Encoder + GL + RTMP session are untouched.

## PART 1 additions (incremental, non-destructive)

### 1. Professional loop / playlist engine — `streaming/PlaylistEngine.kt`
* Modes: `LOOP_ONE`, `LOOP_ALL` (sequential, repeats), `SHUFFLE` (no immediate repeat),
  `PLAY_ONCE` (explicit "end live after the playlist" — user choice, never a boundary bug).
* Single video → decoder `loopMode = true` (unchanged proven path, zero boundary cost).
* Multi-video boundary → `VideoFileSource.replaceFile()` + `MixedFileAudioSource.replaceFile()`
  on a dedicated transition executor. **No** encoder restart, **no** RTMP reconnect,
  **no** service restart, **no** timer reset.
* `GlStreamInterface.setForceRender(true, 5)` while live in video mode: during the sub-second
  decoder swap the last frame is re-rendered at 5 fps so the encoder keeps receiving frames —
  no viewer-visible freeze, no RTMP starvation.
* Audio-format validation: every playlist item must match the AAC encoder format
  (sample rate / channel count) or the item is rejected with a clear message.
* Scheduled playlist: data model + UI slot reserved (future), not fake-implemented.

### 2. Timestamp continuity — `streaming/TimelineGuard.kt`
Continuously validates the OUTPUT timeline while live:
* output duration must be monotonically increasing (regression = the 50→60→50 bug),
* duplicate / stalled timestamp detection (no frames encoded while wall clock advances),
* timestamp jump detection (duration jumps ahead of wall clock),
* loop-boundary validation (timeline must survive every boundary event),
* A/V sync drift monitoring (audio vs video sent-frame pacing).
Violations surface in Live Health; recovery is targeted (decoder/audio source), never
an encoder or RTMP restart.

### 3. Multi-destination — RootEncoder `MultiStream`
* DIRECT mode, 2+ destinations: one encoder, N `RtmpClient`s, each with its own
  `ConnectChecker` → independent state, independent bounded backoff reconnect,
  independent stop. One destination failing never touches the others.
* DIRECT mode, 1 destination: **unchanged `RtmpStream` path** (exact legacy behavior).
* Destination count is data-driven (3, 5, 10 … limited by network reality, not code).

### 4. Smart Relay — `relay/RelaySessionClient.kt` + `relay-server/`
```
PHONE ── ONE upstream RTMP ──► LIVE VIP RELAY ──┬─► YouTube A
                                              ├─► YouTube B
                                              └─► YouTube C
```
* App registers destinations with the relay over HTTPS (token auth) and streams ONE
  upstream. Phone upload stays at 1× regardless of destination count.
* Relay server (Node.js + node-media-server ingest + `ffmpeg -c copy` fan-out,
  per-destination restart with backoff) ships in `relay-server/` with Dockerfile.
* Stream keys travel only over HTTPS to the user's own relay; never logged.

### 5. Capabilities & quality — `streaming/CapabilityDetector.kt`, `streaming/QualityProfiles.kt`
* Hardware AVC encoder max size from `MediaCodecList`; source resolution/fps from
  `MediaAnalyzer`. A resolution/fps option is enabled only if source AND encoder support
  it — otherwise the UI states e.g. “4K unavailable on this device”. No fake 4K, no fake
  upscaling. UHD-ready sizes are first-class in the model even when disabled.
* 360p/480p/720p/1080p/1440p/2160p, 24/25/30/50/60 fps, bitrate presets + custom,
  ~2 s keyframes, CBR-preferred (hardware CBR where supported).

### 6. Network pre-flight — `streaming/NetworkAssessor.kt`
Required upload = video + audio + protocol overhead (~10%) + safety margin (25%).
Shows required vs available (link estimate when the OS provides one, RTMP-host latency
probe, honest “estimate unavailable — monitored live” otherwise) and recommends
DIRECT vs SMART RELAY: direct with N destinations needs ~N× the upload.

### 7. Projects & history — `data/` (SQLite, no annotation processors for build reliability)
* Tables: projects, destinations, playlist items, overlays, scenes, stream sessions.
* Stream keys / relay tokens live ONLY in the Keystore-backed `SecretsVault` —
  never in the database, never in plain prefs, never in logs.
* Legacy migration: existing saved URL/key/profiles/videos become a “Quick Live” project,
  so current users keep their exact working setup.

### 8. Overlays composited into the stream — `overlay/`
* Overlay types render through RootEncoder's GL filter chain (`GlStreamInterface`),
  i.e. into the ENCODED frames — not Android UI chrome: text, image/logo/watermark,
  lower third, clock, countdown, scrolling text, border/frame.
* Toggling an overlay during live = add/remove GL filter. RTMP is never reconnected.
* Scenes = named overlay sets; switching scenes diffs the filter list live.

### 9. Watchdogs & Live Health — `streaming/WatchdogCenter.kt`
Decoder / encoder / audio / RTMP-per-destination / network / memory / thermal monitors
with targeted recovery (restart the stalled source only). App restart is last resort.

### 10. UI (premium dark, video-first)
VIDEO LIVE is the primary source; CAMERA secondary. Home = status, preview, current
project, recent projects, edit/start live, and the live dashboard (stats, per-destination
states, audio/overlay/scene/chat/analytics controls, stop). Honest empty states for
chat/analytics until official platform accounts are connected (Part 2 / OAuth).
Floating LIVE bubble (overlay permission) opens the dashboard; closing it never stops live.

### 11. Background safety
Foreground service owns the stream (unchanged). Dynamic FGS types (mediaPlayback for
video mode, camera|microphone for camera mode). Battery-optimization risk detection with
“Fix now” guidance; OEM-specific notes. Never a promise that Android can't kill us.

## What is NOT faked
* No fake analytics/chat — official-API placeholders state “connect platform account”.
* No fake 4K — capability-gated.
* No demo/mock streaming — all paths drive the real RootEncoder pipeline.


## Part 1 UI implementation map (final)

| Screen | File | Notes |
|---|---|---|
| Home + Live Dashboard | `ui/HomeActivity.kt`, `layout/activity_home.xml` | VIDEO/CAMERA toggle (locked while live), battery banner + FIX NOW, preview with READY badge, live dashboard: 8 stats (bitrate, fps, dropped, connection, loops, reconnects, destinations, timeline ✓/!/✗), per-destination rows (🟢 LIVE / 🔴 FAILED / RECONNECTING with retries + error), skip prev/next, AUDIO/OVERLAY/SCENES/CHAT/ANALYTICS/STOP controls, project card, quick actions, recent projects |
| Video Library | `ui/VideoLibraryActivity.kt`, `layout/activity_video_library.xml`, `item_video.xml` | real thumbnails (ThumbnailCache disk+memory LRU), name/duration/resolution/FPS/codecs/size, Preview (VideoView), Select, Add-to-playlist, Rename, Details, Delete — URI references only, never duplicated |
| Projects | `ui/ProjectsActivity.kt`, `item_project.xml` | project hub with history (last streamed, count), open/edit/duplicate/delete |
| Project editor | `ui/ProjectEditorActivity.kt`, `activity_project_editor.xml`, `item_playlist_row/destination_row/overlay_row/scene_row` | source toggle, playlist CRUD + reorder + audio-format homogeneity guard, loop-mode dropdown (Loop One/All/Shuffle/Play Once), broadcast mode radio, N destinations (stream keys → SecretsVault only), metadata, capability-gated resolution/fps/bitrate dropdowns, audio mixer defaults, overlays, scenes |
| Settings | `ui/SettingsActivity.kt` + relay/background/quick-live sections | relay URL + token (encrypted) with TEST CONNECTION, floating bubble toggle + overlay permission, battery fix, legacy quick-live destination |
| Dialogs | `dialog_network_check.xml`, `dialog_audio_mixer.xml`, `dialog_live_controls.xml` | pre-flight (Required/Available/Margin/Recommendation + host latency probe), mixer with live RMS level meters, honest chat/analytics placeholders |

## Part 1 unit tests (CI: `gradle testDebugUnitTest`)

* `PlaylistEngineTest` — loop-all wrap, loop-one, shuffle (no immediate repeats across 20 seeds), play-once natural end, manual skips never end the broadcast, bounded failure budget.
* `TimelineGuardTest` — regression (timeline moved backward) is CRITICAL, loop-boundary continuity validated, boundary timestamp reset detected, stall/jump/A-V drift detection.
* `WatchdogCenterTest` — decoder stall → RESTART_VIDEO_SOURCE only, encoder stall → keyframe request (not restart), network/memory/thermal are REPORTs, bounded restart budget.
* `NetworkMathTest` — required = video+audio+overhead+25% margin, DIRECT multiplies by N, relay stays 1×, EXCELLENT/GOOD/TIGHT/INSUFFICIENT, unknown upload never invents a number.
* `QualityProfilesTest` — 360p→4K ladder, ~2s keyframes, no upscaling/encoder-cap violations, bitrate ordering, loop-mode name persistence.
* `RelaySessionClientWireTest` — create body, ingest url/key (= sessionId), status parsing — matches relay-server's verified API.
* `OverlayConfigTest` — JSON round-trips for every overlay type + scene lists.
* `PcmRingBufferTest` — FIFO order across wrap-around, overflow drops OLDEST (keeps newest — fixed), underrun reads silence.
