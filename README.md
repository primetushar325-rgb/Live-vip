# LIVE VIP 2.0 🟣

A professional Android **Video Loop Live Streaming** app — stream a local video file on an
endless gapless loop to any RTMP/RTMPS endpoint, mix in your microphone on top of the video's
original audio, or switch to classic camera streaming. Package: **`com.livevip.app`**.

```
SELECT VIDEO → ANALYZE → PREVIEW → MIX AUDIO → START LIVE → LOOP FOREVER → RTMP/RTMPS → LIVE
```

Two fully independent modes, one stream pipeline:

- 🎬 **VIDEO LIVE** (default) — loop a video from your device, with its original audio, forever.
- 📷 **CAMERA LIVE** — front/back camera with flash, like a classic IRL streaming app.

---

## Download APKs

Every push builds both APKs on GitHub Actions:

- **GitHub → Releases** → latest `apk-build-N` release
  - `LiveVIP-v1.0.0-release.apk` (signed, ~10.6 MB)
  - `LiveVIP-v1.0.0-debug.apk`
- Or **GitHub → Actions → "Build Live VIP APK" → latest run → Artifacts**.

## Installation

1. Download `LiveVIP-v1.0.0-release.apk` (Android 8.0+ / API 26+).
2. Allow "Install unknown apps" for your browser/file manager when prompted.
3. Tap the APK → Install → Open.

---

## Feature list

### Video Loop Live (the core)
- **Select Video** via the system picker (`video/*` — MP4/H.264 plus anything the device decodes).
- On import the file is **analyzed** (codec, resolution, FPS, duration, audio codec, sample rate,
  channels, bitrate, size) — unsupported files get a friendly message, never a crash.
- **Video Library**: every imported video is saved as a *reference* (persistable URI permission —
  no file duplication). Cards show thumbnail + metadata with Use for Live / Rename / Info / Delete.
- **Gapless infinite loop**: the decoder seamlessly restarts the file *inside* a running
  encoder/RTMP session. **The stream NEVER disconnects or restarts when the video ends.**
  Output timestamps stay monotonic across loops — no 0→60/0→60 timestamp regressions.
- Video is scaled by the hardware pipeline to the selected output resolution (360p–1080p, 24/30/60 FPS, 800–6000 kbps).

### Independent audio mixer (critical rule honored)
Two completely independent audio sources mixed in software (PCM16, clamped saturation mix):
- 🎵 **Video original audio** — ON/OFF + volume 0–100 %.
- 🎙 **Microphone** — ON/OFF + volume 0–100 %.
- **Muting the mic removes ONLY the mic.** The video's own audio keeps streaming untouched
  (and vice-versa). Both off → clean silence, stream stays up.

### Streaming engine
- **RTMP and RTMPS**, stream key masked in the UI and **never logged**.
- **Stream Profiles**: save name/platform/URL/key/quality/FPS/bitrate — keys stored in
  **encrypted preferences** (androidx.security-crypto).
- Guided start flow: `Preparing Video → Initializing Encoder → Connecting → LIVE` with
  per-step error messages, plus **preflight checks** (video selected, URL valid, network online,
  permissions) before anything starts.
- **Live dashboard**: duration, live bitrate, FPS, dropped frames, connection quality, loop count,
  reconnect count.
- **Auto-reconnect** with exponential backoff (1 → 2 → 4 → 8 → 16 s) and full socket cleanup
  between attempts.
- **Foreground service**: streaming survives screen lock, app background, and activity
  recreation. The notification shows live bitrate/FPS and has a **STOP LIVE** action.
- Stop requires a **confirmation dialog** — no accidental end-of-stream.
- Hardware encoders/decoders (MediaCodec) throughout; resources fully released so a
  second/third stream in the same session starts cleanly.

### UI / UX
- Premium dark theme: background `#0B0B10`, cards `#15151D`, primary purple `#7C4DFF`,
  cyan accent `#00C2FF`. Red appears **only** for errors/danger/STOP.
- Home: header, live preview surface, SOURCE segmented control (Video/Camera), selected-video
  card with thumbnail + metadata + Change Video, audio mixer card, stream configuration,
  pulsing LIVE badge.

---

## How to go Video-Loop live

1. Open the app → **VIDEO** source is pre-selected.
2. Tap **Select Video** → pick any video from your phone → metadata card appears.
3. Set **Video audio** / **Microphone** switches and volumes in the Audio Mixer.
4. Pick platform preset or Custom RTMP(S), paste **Stream URL** + **Stream Key** (masked),
   choose quality/FPS/bitrate — or load a saved **Profile**.
5. Press **START LIVE**. The video loops forever; lock the screen, switch apps — it keeps going.
6. Stop via the button (confirmation dialog) or the notification's **STOP** action.

---

## Architecture

```
app/src/main/java/com/livevip/app/
├── ui/            HomeActivity (modes, mixer, dashboard), VideoLibraryActivity, SettingsActivity
├── media/         MediaAnalyzer (MediaMetadataRetriever/MediaExtractor), VideoRepository (reference-based library)
├── audio/         MixedFileAudioSource — file-audio decoder + mic, software PCM16 mixer
├── streaming/     LiveStreamingManager (single owner of the RootEncoder pipeline), StreamConfig, StreamState/Stats
├── service/       LiveStreamingService — foreground service, notification with stats + STOP
├── data/          SettingsRepository, StreamProfilesRepository (EncryptedSharedPreferences)
├── camera/        CameraConfig
└── util/          NetworkMonitor
```

**Independent lifecycles (anti-freeze design):** the RTMP connection, the encoder, and the
video decoder are decoupled. A loop restarts only the *decoder read position*; the encoder
timeline and the socket are untouched. Reconnect restarts only the socket; the decode/encode
pipeline keeps producing. The UI binds/unbinds freely — the foreground service owns the stream.

**Audio independence — how it works:** `MixedFileAudioSource` decodes the video file's audio
track to PCM on its own thread and runs the microphone as a *separate* capture into a ring
buffer. Each outgoing frame is `clamp(file·videoVol + mic·micVol)` per 16-bit sample. The mic
switch only zeroes the mic term — the file decoder never pauses, so video audio is bit-perfect
whether the mic is on or off. Mic underruns are zero-filled; the stream never starves.

---

## Build from source

Requirements: JDK 17, Android SDK 35. No local keystore needed for debug.

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew assembleRelease    # signed with keystore via env vars (see .github/workflows)
```

Stack: AGP 8.7.3 · Kotlin 2.2.20 · compileSdk 35 / minSdk 26 · Material 3 ·
RootEncoder 2.8.1 (RTMP/RTMPS + MediaCodec pipeline) · androidx.security-crypto.

---

## Known limitations

- **Loop mode is Loop Forever only.** "Play Once" / "Loop X times" are not yet wired into the UI
  (the pipeline hard-codes gapless infinite loop, which is the default behaviour anyway).
- **Aspect ratio is fixed to full-frame scaling** — RootEncoder 2.8.1's `GlStreamInterface`
  hard-codes `AspectRatioMode.NONE` for stream output, so Fit/Fill/Stretch selection is not
  exposed. Pick an output resolution matching your video's aspect for best results.
- **Playlist (multi-video queue)** is deferred; the architecture (reference-based library +
  `changeVideoSource` without encoder restart) is ready for it.
- Scheduling/auto-start is architecture-ready but has no UI.
- DRM-protected or exotic-codec files fail analysis gracefully with a message (by design).

## Testing report (manual matrix)

| # | Test | Result |
|---|------|--------|
| 1 | Select video → metadata shown correctly | ✅ |
| 2 | Start VIDEO live → RTMP connects, video+audio play | ✅ |
| 3 | Video ends → loops gaplessly, stream stays connected | ✅ |
| 4 | Mic OFF → video audio continues alone | ✅ |
| 5 | Mic ON + video audio → both mixed | ✅ |
| 6 | Video audio OFF + mic ON → mic only | ✅ |
| 7 | Screen lock / background → stream continues (foreground service) | ✅ |
| 8 | Network drop → exponential backoff reconnect | ✅ |
| 9 | Stop → start again without app restart → no crash | ✅ |
| 10 | Unsupported file → friendly error, no crash | ✅ |

*(Automated CI builds both APKs on every push; runtime tests are manual on-device.)*
