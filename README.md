# LIVE VIP

A clean Android live-broadcasting rebuild. The source tree starts from an empty repository and is intentionally split into explicit media responsibilities:

- `stream/` — `LiveStreamingEngine`, explicit state machine, master clock, reconnect policy, foreground service
- `video/` — `VideoSourceController` using `MediaExtractor` + hardware `MediaCodec` decoder
- `compositor/` — OpenGL ES surface compositor; no Bitmap frame copies
- `encoder/` — hardware H.264 surface encoder
- `audio/` — source AAC pipeline with monotonic timestamps
- `rtmp/` — direct RTMP/RTMPS handshake, AMF command flow, FLV H.264/AAC packets
- `network/` — Android network callback monitoring
- `ui/` — offline-first preview, FIT/FILL/RESET, drag, pinch, double-tap reset

## Build

Open the project in Android Studio with Android SDK 35 and JDK 17, or run:

```bash
gradle clean test assembleRelease
```

The release artifact is written to `app/build/outputs/apk/release/app-release-unsigned.apk` unless signing is configured. This repository does not contain a signing key.

## Runtime notes

- The video picker uses persisted document permissions and the preview plays locally before streaming. Preview does not depend on a server or stream key.
- Stream keys are stored with AndroidX `EncryptedSharedPreferences` and are never included in diagnostics.
- LIVE VIP only accepts direct `rtmp://` and `rtmps://` destinations. No relay, relay token, canvas, scenes, templates, timers, fake metrics, or fake LIVE state are included.
- A source video AAC track is passed through. Microphone + source-audio mixing is rejected with an explicit `UNSUPPORTED_AUDIO_MIX` error rather than silently producing incorrect audio; microphone-only mixing is the next isolated implementation milestone.
- YouTube acceptance still requires a real Live Control Room check. A successful RTMPS socket is reported as `CONNECTED`/`SENDING`; the app does not claim YouTube ingest without actual media packets.

## Verification status

This checkout was created in an environment without Java/Android SDK, so an APK could not be built or device tests could not be truthfully claimed here. The tests included in `app/src/test` cover composition aspect-ratio invariants, state transitions, timestamp monotonicity, reconnect backoff, and Annex-B to AVCC conversion. Run the build and device acceptance matrix on an Android/Gradle environment before release.
