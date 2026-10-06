# Live VIP — Part 2 Architecture: Professional Live Canvas / Scene Editor

> Status: **BUILT + CI-VERIFIED** (run 37455032421, 105 unit tests, debug + release APK).
> Built ON TOP of Part 1 (see `ARCHITECTURE-PART1.md`) without replacing any
> working engine component.

## 1. The one rule that shaped everything

**Preview == broadcast.** Nothing canvas-related is UI chrome. The editor
preview runs through the exact same RootEncoder GL filter chain that feeds
the encoder surface, and every layer is a GL filter composited into the
encoded H.264 frames. Editing aids (grid, safe area, snap, selection box)
live in a plain Android `View` above the preview — they are physically
incapable of reaching the stream.

## 2. Frame path (Part 2 additions in bold)

```
SOURCE (file decoder / camera)
  → GlStreamInterface filter chain:
      [0] CanvasVideoTransformRender   ← THE CANVAS (new)
          glClear(background) + transformed main-video quad
      [1..n] layer renders             ← LAYERS (extended)
          text / image / PiP video(OES) / GIF / subscribe /
          clock / countdown / scroll / lower-third / border / background
  → ENCODED FBO → H.264 → MUX → RTMP/RTMPS → relay/destinations

AUDIO (unchanged from Part 1):
  video audio + mic → MixedFileAudioSource → AAC → MUX
```

## 3. Core components

| File | Role |
|---|---|
| `overlay/CanvasConfig.kt` | `CanvasAspect` (16:9/9:16/1:1/4:5/CUSTOM), `FitMode`, `OverlayAnimation`, `VideoTransform` (pure math: `quadFor()` → 20-float NDC triangle-strip), `CanvasConfig` (persisted JSON). `width×height` IS the encoder resolution. |
| `streaming/CanvasPresets.kt` | Real resolutions per aspect + **honest validation** (even dims, encoder caps, performance class, bitrate floor) with suggestions ("1080×1920 at 60 FPS not supported — use 720×1280 at 30 FPS"). |
| `overlay/CanvasRenders.kt` | `CanvasVideoTransformRender` (background + transformed quad, thread-safe live buffer swap), `AnimatedImage/TextFilterRender` (absolute-base sprite math — no long-run drift), `GifLayerRender` (Movie API, ONE reused bitmap ≤512px), `VideoLayerRender` (silent looping MediaPlayer → SurfaceTexture → OES composited; bounded 5s×50ms GL-surface retry), `SubscribeArt` (canvas-drawn pill+bell), `LayerAnimations` (pure time-based math). |
| `overlay/OverlayFilterFactory.kt` | Builds per-layer GL renders; rotation + animation for all object renders; `decodeImage()` downsampled via `inSampleSize` (maxEdge 2048 — a 4K photo never fully enters RAM); `needsRebuild()` vs **in-place `updateInPlace()`** (position/scale/rotation/opacity = direct sprite update, zero filter churn); `LayerRender` interface for player/bitmap release. |
| `streaming/LiveStreamingManager.kt` | `applyCanvasInternal()` (canvas = filter 0, `addFilter(0, …)`), z-order-aware `applyOverlays()` (reorder = remove+re-add only; change = in-place or single-layer rebuild), `updateVideoTransform()` (live gestures), `previewCanvas()` (editor preview: re-prepares encoders AT canvas dims so preview is pixel-equivalent), `applyScene()` (overlay set diff). |
| `ui/CanvasEditorActivity.kt` | The editor: aspect chips + validated resolution list, large canvas preview, drag/pinch/rotate gestures with snap, transform row (FIT/FILL/CENTER/ZOOM/ROTATE/RESET/STRETCH-with-confirm), LAYERS panel (reorder/hide/lock/duplicate/delete/properties), SCENES panel, TEMPLATES panel, +ADD via system document picker (persistable URI grants). Aspect/resolution locked while live; everything else editable live. |
| `ui/CanvasAidsView.kt` | Grid / rule-of-thirds / safe area / selection handles — UI-only. |
| `data` layer | `projects.canvas_json` (DB v2, ALTER TABLE migration), `overlay_templates` table (reusable "Gaming Overlay" style templates), `updateProjectShell()` partial save (destinations/playlist/keys untouched), `buildPlan` passes `canvas`. |
| `streaming/BroadcastPlan.kt` | Validates canvas == encoder dims, even, in-range, encoder-capable before going live. |
| `ui/HomeActivity.kt` | SHORTS LIVE / LANDSCAPE LIVE buttons → project preconfigured with a validated 9:16 / 16:9 canvas → straight into the editor. |

## 4. Live semantics (non-negotiables honored)

- **Scene switch / overlay change / text change / transform drag during LIVE**
  = GL filter add/remove/update only. RTMP socket, encoder, audio mixer and
  the foreground service are never touched. No timestamp resets.
- **Encoder restart** happens only when the canvas RESOLUTION changes, which
  is locked while live anyway.
- **No default stretch.** FIT (contain) is the default; STRETCH requires an
  explicit confirm dialog.
- **Looping + canvas:** the loop boundary callback (Part 1) never touches
  filters — canvas and layers persist across loops by construction.
- **Mic mute ≠ video audio stop:** PiP video layers are separately silent;
  the main video audio path is Part 1's mixer, untouched.
- **Memory:** images downsampled at decode; GIFs never fully preloaded
  (one reused bitmap); PiP buffers sized to display size, not encoder size;
  `releaseLayer()` frees players on every removal path.

## 5. Tests (unit, pure logic — all in CI)

- `VideoTransformTest` — NDC quads: full-frame, 16:9→9:16 pillarbox without
  stretch, FILL cover+crop, STRETCH, zoom, pan, 90° rotation vertex map,
  crop UV sub-rect, JSON round-trip.
- `CanvasPresetsTest` — even-dimension guarantees, portrait presets,
  encoder-cap fallbacks, "60 FPS → 30 FPS" suggestion, missing-encoder
  honesty.
- `LayerAnimationsTest` — identity for NONE, bounds + periodicity for every
  animation.
- `CanvasConfigJsonTest` — canvas/overlay/scene JSON round-trips, legacy
  Part 1 JSON parses with defaults, z-order preservation, aspect inference.

## 6. Known limitations (honest list)

1. Device-level soak tests (1/3/6/10 h, background, screen-off) require a
   physical device — the code is leak-bounded by design (single reused
   buffers, bounded retries, release-on-removal) but was not soak-tested
   in CI.
2. GIF layers assume the compositor can keep ~25 fps advance on large GIFs;
   files >4 MB warn the user.
3. PiP video layers render at a fixed 16:9 buffer — a non-16:9 PiP source
   is letterboxed by MediaPlayer into that buffer.
4. Web/browser layer type from the spec is not implemented (declared
   out-of-scope for the GL compositor on Android without a WebView capture
   path).
5. Text layers use the platform `TextStreamObject` renderer — custom font
   files are not yet user-selectable (bold default, size/color/bg are).
6. Clock/countdown/lower-third layers rebuild on geometry change (their
   1 Hz tick closures capture build-time config) — cost equals their normal
   per-second text refresh.

## 7. Part 1 intact

Part 1's last standalone commit (`04845a1`) was green (run 37450556135).
Part 2 changes are additive: engine, service, audio, watchdogs, relay,
projects DB (v1→v2 with migration), and all Part 1 behaviors unchanged —
the 60+ Part 1 unit tests still pass in the Part 2 CI run.
