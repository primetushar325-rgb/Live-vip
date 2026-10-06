# LIVE VIP 🔴

A modern, professional Android live-streaming application — a lightweight mobile streaming studio.
Built completely from scratch. Package: **`com.livevip.app`**.

```
INSTALL → LAUNCH → HOME → CAMERA PREVIEW → MIC → SETTINGS → START LIVE → RTMP/RTMPS → LIVE
```

Every stage works independently. The app always opens to the Home screen first — **nothing heavy
(camera / mic / encoder / RTMP) is initialized at application startup.**

---

## Download APKs

Every push builds both APKs on GitHub Actions:

- **GitHub → Actions → "Build Live VIP APK" → latest run → Artifacts**
  - `LiveVIP-debug-apk`
  - `LiveVIP-release-apk` (signed)
- Or **GitHub → Releases** → latest `apk-build-N` release.

## Installation

1. Download `LiveVIP-v1.0.0-release.apk` to your phone (Android 8.0+ / API 26+, tested config targets Android 14).
2. Allow "Install unknown apps" for your browser/file manager when prompted.
3. Tap the APK → Install → Open.
4. On first launch you land on the **LIVE VIP** home screen. Tap **Enable Camera** to grant
   camera permission and see the preview. Microphone + notification permissions are requested
   only when you press **START LIVE** — never at startup.

## How to go live

1. Pick a **Stream Platform** (Custom RTMP / Custom RTMPS / YouTube / Facebook / Twitch presets).
2. Enter the **Stream URL** (e.g. `rtmp://a.rtmp.youtube.com/live2`) and your **Stream Key**
   (hidden by default — use the eye toggle).
3. Choose quality (360p/480p/720p/1080p), FPS (24/30/60) and bitrate.
4. Press **START LIVE**. Status flows `OFFLINE → CONNECTING → LIVE ●` with a timer, bitrate,
   dropped frames and connection quality. A persistent notification with a **Stop Stream**
   action keeps the stream alive in the background (foreground service).

---

## Building from source

Requirements: JDK 17, Android SDK (compileSdk 34). Internet access for Google Maven,
Maven Central and JitPack.

```bash
# Debug APK
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk

# Signed release APK
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

Or open the project in Android Studio (Hedgehog or newer) and run the `app` configuration.

> **Release signing:** `keystore/livevip-release.p12` (PKCS12, alias `livevip`,
> password `livevip123`) is a committed demo key so the release build is reproducible.
> For Play Store distribution, replace it with your own private keystore and move the
> credentials out of version control.

## Architecture

```
app/src/main/java/com/livevip/app/
├── LiveVipApplication.kt        # lightweight startup only (theme + notification channel)
├── ui/
│   ├── HomeActivity.kt          # dashboard — UI only, no streaming logic
│   └── SettingsActivity.kt      # Video / Audio / Stream / Appearance / Advanced
├── camera/
│   └── CameraConfig.kt          # presets + closest-supported-resolution fallback
├── streaming/
│   ├── LiveStreamingManager.kt  # central engine (state machine, reconnect, stats)
│   ├── StreamConfig.kt          # per-session config + URL/key combination
│   └── StreamState.kt           # OFFLINE/CONNECTING/LIVE/RECONNECTING/ERROR + stats
├── service/
│   └── LiveStreamingService.kt  # Android 14-compliant foreground service + notification
├── data/
│   └── SettingsRepository.kt    # prefs + EncryptedSharedPreferences for credentials
└── util/
    └── NetworkMonitor.kt        # Wi-Fi/cellular detection, loss/recovery callbacks
```

**Streaming pipeline** (per the spec, provided by the actively-maintained
[RootEncoder](https://github.com/pedroSG94/RootEncoder) 2.8.1 engine rather than
hand-rolled, unreliable code):

```
LiveStreamingManager → Camera2 capture → H.264 hardware VideoEncoder
                                       → AAC AudioEncoder
                                       → FLV Muxer → RTMP/RTMPS client → server
```

## Implemented features

- Home dashboard: camera preview, connection + stream status, URL/key inputs, platform
  selector, quality/FPS/bitrate selectors, large START/STOP LIVE button, camera switch,
  mic mute, flash toggle, settings
- RTMP **and** RTMPS custom servers + YouTube/Facebook/Twitch presets; URL + key combined
  correctly; no hard-coded credentials
- Front/back camera, switching (also while live), flash where supported, tap-free auto focus,
  360p–1080p, 24/30/60 fps, graceful fallback to the closest supported resolution/configuration
- Microphone: permission requested only at stream start, mute/unmute while live,
  echo cancellation + noise suppression, configurable bitrate/sample rate/stereo,
  full cleanup on stop
- Hardware H.264 + AAC encoding with configurable resolution/fps/bitrate/keyframe interval;
  automatic safe fallback (640×480@30) instead of crashing on unsupported configs
- Foreground service (Android 14 `camera|microphone` types) with persistent
  "LIVE VIP — Streaming live" notification: duration, connection status, Stop action;
  released immediately when the stream stops
- Real-time status: OFFLINE / CONNECTING / LIVE ● / RECONNECTING / ERROR with timer,
  bitrate (kbps), FPS, dropped frames, connection GOOD/POOR
- Auto-reconnect with exponential backoff (2s → 4s → 8s …, capped), bounded attempts,
  "Stream disconnected" dialog + full resource release on repeated failure
- Network handling: availability check before start ("No internet connection."),
  Wi-Fi/cellular detection, loss/recovery callbacks — no crashes
- Security: stream keys stored in Android-Keystore-backed EncryptedSharedPreferences,
  hidden by default with show/hide toggle, never logged (engine logs disabled,
  URLs sanitized), no analytics
- Material 3 premium dark UI (light/system optional), rounded cards, one-hand layout,
  readable error messages for every failure path

## Known limitations

- Audio level meter is not displayed (mute state + bitrate stats only).
- FPS stat shows the configured encoder FPS, not a per-frame measurement.
- Portrait orientation is locked by design for stream stability (prevents mid-stream
  surface/rotation renegotiation).
- Settings changed while live apply from the next stream.
- The committed demo signing key is for sideloading/testing; replace it for store release.
- Real-device verification (camera/encoder behaviour differs per OEM) should be performed
  on your handset; the CI build guarantees compile, packaging and signature validity.

## Crash-safety checklist covered

Fresh install • first launch • permissions denied/granted • camera/mic unavailable •
no network • wrong URL/key • server disconnect • mid-stream network loss •
background/foreground • stop/start cycles • screen rotation (locked) — every failure path
ends in a readable error and a usable Home screen, never a silent crash.
