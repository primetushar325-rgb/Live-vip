# LIVE VIP — Part 5: Clean OBS-Style Rebuild

**Scope:** rebuild the app into a clean, simple, production-ready mobile live
broadcaster. This supersedes the studio features of Parts 1–3 (canvas editor,
scenes, templates, overlays, timers, playlists, Smart Relay, projects) while
**keeping** the streaming engine and every Part 4 reliability contract.

## What was REMOVED (complete)

| System | Files |
|---|---|
| Canvas / Layer editor | `ui/CanvasEditorActivity.kt`, `ui/CanvasAidsView.kt`, `overlay/OverlayConfig.kt`, `overlay/OverlayFilterFactory.kt`, `overlay/OverlayRenders.kt` |
| Projects / scenes / templates | `ui/ProjectsActivity.kt`, `ui/ProjectEditorActivity.kt`, `ui/NewLiveActivity.kt`, `data/ProjectModels.kt`, `data/ProjectRepository.kt`, `data/LegacyMigrator.kt`, `data/db/` |
| Playlists | `streaming/PlaylistEngine.kt`, `streaming/BroadcastPlan.kt`, `streaming/LoopMode.kt` (playlist modes) |
| Smart Relay (all of it) | `relay/RelaySessionClient.kt`, `relay-server/` (server + tests), relay UI/errors/tokens |
| Multi-destination | `DestinationConfig`, `DestinationRuntime`, `MultiStream` usage in the manager |
| Profiles / settings screens | `data/StreamProfilesRepository.kt`, `ui/SettingsActivity.kt`, `ui/VideoLibraryActivity.kt` |
| Legacy helpers | `data/SecretsVault.kt`, `data/ThumbnailCache.kt`, `camera/CameraConfig.kt`, `util/BatterySafety.kt` |
| 20 layouts, 6 tests | everything except `activity_home.xml` |

`grep -rni "relay|token|https" app/src/main` → zero relay references.

## What was KEPT (engine + Part 4 contracts)

- RootEncoder RTMP/RTMPS engine (one `RtmpStream`, direct ingest).
- **Loop boundary**: decoder-internal restart; encoder/RTMP never touched;
  monotonic PTS/DTS (TimelineGuard live check). `onVideoLoop()` = counter +
  timeline mark + audio resync (AudioLoopSync).
- **Ingest verification**: PUBLISHING → LIVE only after sustained verified
  media flow (IngestVerifier, 6 s window).
- WatchdogCenter targeted recovery (video source / audio source / keyframe),
  bounded reconnect backoff 1 s→30 s, network watchdog.
- Foreground service ownership: UI never stops a stream; STOP is explicit.
- Stream key security: EncryptedSharedPreferences, never logged, password
  field with show/hide toggle.
- Honest metrics only: bitrate/fps/dropped/A-V sync/components from the real
  pipeline.

## The new architecture (one pipeline)

```
VIDEO FILE → VideoFileSource (internal loop)
           → GL compositor (CanvasVideoTransformRender — transform quad)
           → H.264 (HW, CBR, 2 s keyframes) + AAC (video+mic mix)
           → RTMP/RTMPS → SERVER
```

- ONE filter (`CanvasVideoTransformRender`) is FIRST in the GL chain.
  RootEncoder renders the final filtered texture to BOTH the preview surface
  and the encoder surface — **preview == live output, pixel for pixel**.
- Gestures (1-finger drag = pan, pinch = zoom 0.5–5×, double-tap = reset)
  call `LiveStreamingManager.updateVideoTransform()` — a thread-safe quad
  swap. No encoder restart, no RTMP reconnect, no timestamp change.
- Output formats: exactly two — LANDSCAPE 16:9 (1920×1080/1280×720/854×480)
  and VERTICAL 9:16 (1080×1920/720×1280/480×854). The encoded video itself
  carries the aspect; the source is never stretched (FIT/FILL/CUSTOM math in
  `VideoTransform.quadFor`, unit tested).

## Screen-shift bug — root cause and fix

The old home layout sized the preview at a fixed `220dp` height and used
fixed-width (`46dp`) icon buttons in multi-control horizontal rows, with no
window-inset handling. On narrow screens / different densities / landscape
keyboards those fixed rows overflowed the viewport and the fixed-height
preview shifted the content column — the visible "shifted UI".

The new `activity_home.xml`:
- zero fixed dp widths on controls (every row is `layout_weight` shares),
- no negative margins anywhere,
- preview sized in code from the real output aspect (CanvasPreviewMath),
- window insets applied via `ViewCompat.setOnApplyWindowInsetsListener`,
- root ScrollView, `fillViewport`, content centered, no horizontal scroll.

## Saved Live (persistence, no projects)

`SettingsRepository` persists exactly: selected video (content URI + metadata,
reference only — never copied), output format, transform JSON, server URL,
stream key (encrypted), quality, fps, audio prefs, show/hide key toggle.

## Tests

Pure-JVM unit tests (CI): NetworkMath, QualityProfiles, StreamConfigUrl,
TimelineGuard, WatchdogCenter, IngestVerifier, AudioLoopSync, PcmRingBuffer,
VideoTransform, CanvasPreviewMath, CanvasPresets, **SavedLive** (new —
JSON round-trips for selection + transform).

Device-only tests (30 min–8 h soak, lock/background, network loss, activity
recreate, real ingest) cannot run in this sandbox — see the Part 5 report for
the honest status of each.
