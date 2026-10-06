# LIVE VIP — Part 5.1: Critical Regression Fix (Black Preview / False LIVE)

**Scope:** surgical fix of the Part 5 regressions. No rebuild, no new systems,
no canvas/scenes/templates/timers/relay. The streaming engine (RootEncoder
direct RTMP/RTMPS) is unchanged.

## 1. Exact root cause of the BLACK PREVIEW

`HomeActivity` (Part 5 rewrite) removed the surface-lifecycle gating the old
working app had, and violated RootEncoder's preview contract:

- **RootEncoder 2.8.1 `StreamBase.startPreview(surfaceView)` binds
  `holder.surface` ONE-SHOT** (default `autoHandle = false`): no
  `SurfaceHolder.Callback` is registered, `!surface.isValid` →
  `IllegalArgumentException`, and a second call while `isOnPreview` →
  `IllegalStateException`.
- Part 5 changed `previewCard`/`surfaceView` `layoutParams` (resize) and
  called `startPreview(view)` in the SAME tick. The pending layout pass
  destroyed/recreated the surface right after the bind → the engine held a
  DEAD surface. Every later rebind attempt (resume, surface recreation) hit
  `IllegalStateException` (`isOnPreview` still true) which was silently
  swallowed → **permanently black preview**.
- The old working editor gated on its own `SurfaceHolder.Callback`
  (`surfaceReady`) and stopped the preview in `onPause` — exactly the proven
  pattern Part 5 dropped.

**File/class:** `ui/HomeActivity.kt` (`startPreviewPipeline` + layout-change
listener) interacting with `streaming/LiveStreamingManager.startTransformPreview`
(silent `catch` fast path).

## 2. Exact fix

- `HomeActivity`: binding now happens ONLY in `surfaceCreated` (holder
  callback, `surfaceReady` gate); the layout listener only re-SIZES;
  `onPause` stops the preview when not live (resets `isOnPreview`);
  `onResume` rebinds if the engine is not on preview; while LIVE,
  `surfaceCreated` calls the new `LiveStreamingManager.rebindLivePreview()`
  (encoder/RTMP untouched).
- `LiveStreamingManager.startTransformPreview`: explicit
  `holder.surface.isValid` guard with an honest error (never a silent
  swallow); fast path now `stopPreview()` before `startPreview()` when
  `isOnPreview` (heals the dead-surface state) and falls through to a full
  re-prepare on failure; the preview key now includes FPS.
- RootEncoder's own `autoHandle = true` was evaluated and REJECTED:
  `PreviewCallback.onDestroyed → stopPreview(true) → removeCallbacks()`
  unregisters itself permanently — not resize-safe. The manual proven
  pattern is used instead.

## 3. False-LIVE fix (Phase 8/16)

- LIVE was reachable because the encoder really was sending — black frames.
  With the surface fix the pipeline produces real video again.
- Honest state machine (unchanged mechanics, honest labels):
  OFFLINE → CONNECTING → **PUBLISHING ("CONNECTED — SENDING…")** → LIVE
  (only after `IngestVerifier` sees ≥6 s sustained video+audio frames) →
  RECONNECTING / ERROR.
- If the server never accepts media: `IngestVerifier` fails at 20 s with
  "Connected to RTMPS, but video packets are not being accepted. Check the
  stream key…" → state ERROR, real reason shown. Socket-connected ≠ LIVE.
- No fake "1/1 LIVE" / "DESTINATION LIVE" anywhere (grep-verified).

## 4. START LIVE gating (Phase 7)

`startLive` now requires, in order: selected video exists • preview surface
ready • URL/key valid • **saved URI still readable** (ContentResolver probe)
• **decoder actually producing frames** (`LiveStreamingManager.decoderFlowing()`
— two `VideoFileSource.getTime()` samples 250 ms apart must differ). On
failure: "Video preview failed. Please reselect the video." and RTMP never
starts. Encoder init failure and the 854×480 fallback are surfaced in
diagnostics instead of being silent.

## 5. YouTube-safe defaults (Phase 10)

Default profile = LANDSCAPE 16:9, **720p** (1280×720 / 720×1280), 30 FPS,
CBR H.264 + AAC, 2 s keyframes, **4500 kbps** (YouTube 4–6 Mbps band;
720p60 = 5500, 1080p30 = 6000). Unit-tested band checks added.

## 6. Developer diagnostics (Phase 14)

Long-press the app title to toggle. Rows (all real probes, no keys ever):
VIDEO, DECODER, PREVIEW (FRAMES RECEIVED / NO FRAMES), ENCODER (incl. 854×480
fallback flag), AUDIO (sent frame count), RTMP, FPS+dropped, PACKETS SENT
(library `getSentVideoFrames`/`getSentAudioFrames`), BYTES SENT (integrated
from the library-measured socket bitrate), LAST ERROR (sanitized).

## Untouched (Phase 17)

Transform math (`VideoTransform.quadFor`), compositor
(`CanvasVideoTransformRender` — verified byte-equivalent to the previously
working version; RootEncoder's `BaseFilterRender.draw()` performs the
`glDrawArrays`), loop-boundary handling, IngestVerifier window, watchdogs,
reconnect backoff, foreground service, audio mixer, RTMPS engine.
