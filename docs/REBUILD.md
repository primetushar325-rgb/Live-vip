# LIVE VIP — Clean Ground-Up Rebuild (v2.0.0)

Replaced the patched architecture entirely (per the production-rebuild spec).
No code from the old streaming stack was reused except the proven,
unit-tested helpers listed below.

## Removed (and never coming back)

- SMART RELAY (all of it — no relay server, no relay messages, no tokens)
- CANVAS / SCENES / TEMPLATES / TIMERS
- Old `LiveStreamingManager`, `StreamConfig`, `StreamState`, `QualityProfiles`,
  `SettingsRepository`, `VideoRepository`, `LiveVipApplication`,
  `LiveStreamingService`, overlay/ transform stack, 37 unused drawables

Grep proof: `relay|canvas|scene|template` → **0 matches** in `app/src`.

## New architecture

| Layer | File | Responsibility |
|---|---|---|
| core | `LiveCompositionState` | THE ONE composition (shared by preview + encoder), `quadFor()` → GL quad, JSON serde |
| core | `CompositionRenderer` | Single GL filter (BaseFilterRender): clear-black + transformed quad; thread-safe quad swap |
| core | `OutputFormat`/`OutputPresets` | Exactly two formats (16:9 / 9:16), 3 resolutions each, honest AUTO |
| core | `PreviewMath` | Pure preview-surface sizing |
| engine | `LiveEngine` | Singleton orchestrator: offline preview, loop boundary, reconnect (1–30 s backoff, max 5), watchdogs, targeted recovery, honest state machine |
| engine | `EngineConfig`, `BitratePolicy`, `LiveState` | Immutable config; YouTube-safe bitrate ladder; real states only |
| store | `SettingsStore`, `SecureStore`, `LibraryStore`, `SavedLiveStore` | Prefs; EncryptedSharedPreferences (keys); reference-only video library; SAVED LIVES |
| service | `LiveService` | Typed foreground service owns the session (survives activity death) |
| service | `LiveBubbleService` | Optional floating control (close = bubble only) |
| ui | `HomeActivity`, `LibraryActivity` | One responsive screen; gesture transforms; diagnostics (long-press title) |

Kept proven helpers: `streaming/` CapabilityDetector, IngestVerifier,
TimelineGuard, WatchdogCenter, NetworkMath · `audio/` MixedFileAudioSource,
AudioLoopSync · `media/` MediaAnalyzer · `util/` NetworkMonitor.

## Key guarantees

- **Preview == encoder composition**: one `LiveCompositionState` → one GL
  filter → same texture to preview surface and H.264 encoder. Gestures while
  live are a buffer swap — no re-prepare, no reconnect.
- **Loop without restart**: `VideoFileSource(loopMode=true)` + onLoop →
  counter/timeline/audio-resync on a single-thread executor. Encoder, muxer,
  RTMP and timestamps are never touched at EOF.
- **Honest states**: OFFLINE → CONNECTING → CONNECTED → SENDING → STREAMING
  (only after ingest verification) · RECONNECTING · ERROR · STOPPED.
  Socket-connected is never displayed as LIVE.
- **Preview gates START**: RTMP never begins unless the decoder is flowing
  and the URI is readable ("Video preview failed. Please select another video.").
- **Key security**: encrypted storage, password field with show/hide,
  never logged, `rtmp(s)://…` masked in every surfaced error.
- **Stats are real**: bitrate from socket (1 Hz), frames from the RTMP client,
  bytes = Σ bitrate/8; N/A when unmeasured.

## Verification status (honest)

- ✅ Executed: unit tests (`./gradlew test`) in CI — core composition math,
  aspect matrix (16:9→16:9, 16:9→9:16, 9:16→9:16, 9:16→16:9), bitrate
  policy, config/sanitize, saved-live & library JSON round-trips.
- ✅ Executed: CI compile of debug + release APKs.
- ⛔ NOT executed in the sandbox: on-device streaming, YouTube ingest, loop
  endurance, camera mode — no device/emulator exists here. Run TEST 1–25 on
  a real phone with the released APK before relying on them.

## Build steps (performed)

1. `git rm` of the entire legacy stack; tree audited for dangling references
2. All RootEncoder 2.8.1 APIs verified against the library source
   (RtmpStream, StreamBase, GlInterface, BaseFilterRender, GlUtil,
   Camera2Source, SilenceAudioSource, CameraHelper)
3. Layout↔binding id audit, drawable↔color↔string reference audit — 0 dangling
4. CI: clean → compile → unit tests → debug + release APK → release publish
