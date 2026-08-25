# Native Kotlin + Compose Migration — Design

Date: 2026-08-20
Status: Approved (2026-08-20) — full Android migration to 100% native Kotlin + Jetpack
Compose, app renamed `security_cam` → `level1`, Flutter deleted entirely at cutover.

## Goal

Replace the Flutter Android app with a **100% native Kotlin + Jetpack Compose**
application, reusing the existing in-repo `camera_service` Kotlin module, porting all
Dart pipeline/detector/channel/storage logic, implementing camera rotation + zoom
natively (the two known pain points), folding in the five drafted-but-unimplemented
features (tamper, watchdog, monitoring schedule, history gallery, channel `sendTest`
UI), and renaming the app to **level1** with a new package id. iOS is out of scope
(separate later Swift port, matching the original KMP intent).

## Locked decisions (2026-08-20)

| Topic | Decision |
|---|---|
| Flutter codebase | **Deleted entirely** at cutover (Phase 7); kept as a Linux-desktop-only reference harness through Phase 6 |
| UI toolkit | **Jetpack Compose** + Material 3, single-Activity, 3-tab scaffold (Monitor / Events / Settings) mirroring current UX |
| Parity baseline | **Port implemented features AND build the 5 drafted features natively** during the migration |
| In-flight WIP | Committed first as the regression/reference point |
| iOS | Out of scope — Android-only |
| Displayed app name | `level1` (`android:label`) |
| `applicationId` / namespace | `io.securitycam.security_cam` → **`io.securitycam.level2`** (fresh app identity; old installs' data orphaned — no migration) |
| Project directory | `security_cam/` → **`level1/`** (repo becomes `level1/level1/`) |
| MediaStore clips folder | `Movies/SecurityCam/` → **`Movies/level1/`** |
| Internal channel names | Gone — no Dart bridge in the native app |
| Data | Fresh start on-device (new `applicationId`); **no data migration** |
| CameraX | Keep pinned **1.3.4** (1.4.x breaks `bindToLifecycle` overload resolution in Kotlin) |
| Camera bind owner | Keep the `LifecycleService` FGS owning the CameraX bind (screen-off monitoring unchanged); UI attaches/detaches a `PreviewView` surface provider |

## Current state (verified from code, 2026-08-20)

The app is already **hybrid**. A Dart core sits on top of an in-repo native Kotlin
`camera_service` module.

**Already native (1,887 lines Kotlin, ~95% reusable):**

- `MonitoringService.kt` (509) — `LifecycleService` FGS owning CameraX; binds
  `ImageAnalysis` (YUV→BGR, ~4 fps), `ImageCapture`, `VideoCapture<Recorder>`,
  `Preview` (Flutter `SurfaceTexture`); `PARTIAL_WAKE_LOCK`; FGS notification;
  manual `previewRotationDegrees` computation (`MonitoringService.kt:403-421`);
  `captureStill`.
- `VideoClipRecorder.kt` (970) — ring-buffer pre/post-roll clips, MediaExtractor/
  MediaMuxer concat, MediaCodec AAC-LC mux of native mic PCM, MediaStore storage,
  `delete/exists/hasAudio/videoInfo/open`.
- `MicCapture.kt` (114) — `AudioRecord` 16 kHz mono s16le, `VOICE_RECOGNITION`,
  absolute `startSample` timeline.
- `CameraFrameBus.kt` (24) — in-process frame fan-out.
- `CameraServiceChannels.kt` (247) + `MainActivity.kt` (23) — **Flutter bridge;
  deleted in the native app.**

**What lives in Dart (~7,275 lines, must be ported to Kotlin):** all UI (4 screens),
detector pipeline + 6 trigger types (motion, baby cry, glass break, loud noise, face,
person) + regions geometry + event pipeline + trigger batcher + 5 channels + storage
(settings/secrets/events/snapshots) + state machine + `AnalysisDispatcher`
(latest-wins serialization).

**Known camera gaps this migration fixes:**

1. **Rotation (Flutter-specific):** CameraX sets a SurfaceTexture transform matrix
   but **Flutter's Impeller renderer ignores it**, so Dart re-derives
   `previewRotationDegrees` and re-maps region overlays by hand. A native
   `PreviewView`/`TextureView` applies the transform automatically.
2. **Zoom: unimplemented anywhere** (Kotlin and Dart). Net-new natively.
3. **Clips record in sensor orientation** — no `setTargetRotation` on `VideoCapture`
   (one-line fix natively).
4. **Stale rotation** — read once at bind; no `DisplayListener`.
5. **Front-camera mirroring** never conveyed.
6. **CAMERA-permission-missing leak** — `startForeground` + wake lock acquired before
   the permission check; on failure returns with service foreground + wakelock held
   and no `stopSelf()` (`MonitoringService.kt:158-164`).
7. **`START_STICKY` null-intent restart** silently restarts with defaults.

## Target architecture

### Final layout

```
/home/tpa/code/level2/                     (git repo root)
  AGENTS.md                                 (rewritten: native commands, emulator discipline)
  docs/plans/
  level1/                                   (renamed from security_cam/ — the app project)
    android/                                (native Gradle project; Flutter files deleted at Phase 7)
      app/src/main/kotlin/io/securitycam/level1/...
      app/src/main/assets/{yamnet.tflite, yamnet_labels.txt, yolo26n_w8a32.tflite, face.tflite}
      app/src/main/AndroidManifest.xml      (label=level1, package io.securitycam.level2)
    tool/run_android_integration_tests.sh   (reworked for Gradle connectedAndroidTest)
```

Phases 1–6: the Flutter project still lives at `level1/` and runs **desktop-only**
(`flutter run -d linux`) as the reference harness; its `android/` subtree is being
converted to the pure-native app. Flutter Android integration tests stop after
Phase 0 (the native instrumentation suite replaces them).

### Kotlin package structure (`io.securitycam.level2`)

```
MainActivity.kt                      Compose host; runtime permission flow
SecurityCamApp.kt                    root composable; 3-tab scaffold (Monitor/Events/Settings)
di/AppContainer.kt                   manual DI: stores, engines, controller
monitor/
  MonitorViewModel.kt                StateFlow state machine idle→starting→monitoring→error
  MonitorState.kt
camera_service/
  MonitoringService.kt               existing, adapted (drop channels; rotation/zoom; permission leak fix)
  CameraController.kt                CameraX bind; preview surface provider; zoom; rotation; DisplayListener
  CameraFrameBus.kt / MicCapture.kt / VideoClipRecorder.kt   existing, adapted (Movies/level1, clip rotation)
detection/
  Detector.kt / DetectorConfig.kt / DetectorRegistry.kt
  MotionDetector.kt / TamperDetector.kt
  audio/{YamnetClassifier.kt, AudioEventDetectors.kt}   (baby cry / glass / loud noise / dog)
  face/FaceEngine.kt                 MediaPipe Tasks FaceDetector (fallback: extracted BlazeFace tflite)
  person/PersonEngine.kt             YOLO26n CompiledModel + Kotlin letterbox/decode/NMS
  regions/{DetectionRegion.kt, RegionFilter.kt}         (rect+polygon geometry, privacy/inclusion)
  pipeline/{DetectorPipeline.kt, AnalysisDispatcher.kt} (latest-wins serialization)
event/{TriggerBatcher.kt, EventPipeline.kt}
channels/{Channel.kt, TelegramChannel.kt, PushoverChannel.kt, WebhookChannel.kt, EmailChannel.kt, LogChannel.kt}
storage/{SettingsStore.kt, SecretStore.kt, EventStore.kt, SnapshotStore.kt}
watchdog/HealthWatchdog.kt
schedule/MonitoringSchedule.kt
ui/{monitor, events, settings, regions}/    Compose screens
```

### Camera & preview

- The **`LifecycleService` keeps owning the CameraX bind** (verified pattern: bind
  lifecycle = service → camera survives Activity stop / screen off). The Preview use
  case's `setSurfaceProvider` is wired to the Monitor screen's `PreviewView`
  (`previewView.surfaceProvider`) while visible, and cleared when the UI hides —
  the Compose screen never owns the bind lifecycle.
- **Rotation is free** on the display side: `PreviewView` (TextureView-backed,
  `ImplementationMode.COMPATIBLE`) applies CameraX's surface transform → upright
  preview with zero manual math. Region overlays are drawn in display space via the
  same transform (`previewStreamState`/`getTransformationInfo` where needed).
- **Analysis frames stay in sensor space** (unrotated) — the single coordinate
  truth for detectors and regions. The overlay maps sensor → display.
- **Live rotation:** a `DisplayListener` re-reads display rotation; `setTargetRotation`
  re-applied on Preview + `VideoCapture`; `previewRotationDegrees` logic deleted.
- **Front-camera mirroring** handled natively by the texture transform.
- **Clips:** `VideoCapture`/`Recorder` gets `setTargetRotation` so exported clips are
  upright (fixes the sensor-orientation bug).
- **Zoom (net-new):** `CameraControl.setZoomRatio(ratio)` + `animateZoomBy`; pinch via
  Compose `detectTransformGestures`; range from `ZoomState` (min/linear max/max);
  double-tap 1×↔2×; pinch-to-zoom persisted per session only.
- **Orientation lock** (portrait/landscape/sensor setting) applied via
  `requestedOrientation` on the Activity (existing `setOrientation` behavior).
- Camera-selector: back/front unchanged.

### Inference

- **LiteRT v2** (`com.google.ai.edge.litert:litert`) supports both classic
  `Interpreter` and `CompiledModel`. Min SDK 23 ≤ our 24 baseline.
  - YAMNet float32 → classic `Interpreter` (CPU).
  - YOLO26n w8a32 → **`CompiledModel`** (LiteRT Next export requires it; the
    `flutter_litert` on-device path already proved it). Letterbox, decode, IoU NMS
    reimplemented in Kotlin (pure-Dart today). Phase 3 starts with a load spike;
    fallback = re-export the model to classic TFLite + `Interpreter`.
- **BlazeFace** → prefer **MediaPipe Tasks `FaceDetector`** (native, maintained,
  no OpenCV). Fallback: extract the packaged `.tflite` from the
  `face_detection_tflite` plugin and port the postprocess.
- Models live in `android/app/src/main/assets/`.

### Channels & storage

| Area | Native replacement |
|---|---|
| Settings | DataStore (JSON blob, key `app_settings_v1` shape preserved for readability) |
| Channel secrets | Android Keystore via `security-crypto` (`EncryptedSharedPreferences`) |
| Events | **Room** (schema v3 preserved: `id, timestamp, camera_name, trigger_type, score, snapshot_name, video_name, channel_statuses, trigger_types`) |
| Snapshots | `filesDir/snapshots/` JPEG |
| Clips | MediaStore `Movies/level1` (RELATIVE_PATH 29+, DATA 24–28) — existing `VideoClipRecorder` |
| HTTP (Telegram/Pushover/Webhook) | OkHttp + multipart |
| Email | raw SMTP socket (STARTTLS/SSL) or jakarta.mail — decided in Phase 4 after a real-provider test |
| Notification permission | framework `ActivityResultContracts.RequestPermission` |

### State machine

`MonitorViewModel` (ViewModel + `StateFlow<MonitorState>`): `idle → starting →
monitoring → error`, mirroring `MonitorController` (`lib/state/monitor_controller.dart`).
All runtime resources (camera, mic, pipeline, batcher, subscriptions) owned by the
ViewModel and released on stop/dispose; `AnalysisDispatcher` semantics (latest-wins
single-slot) preserved with a `Channel<AnalysisFrame>`-style flow + serialized consumer.

## Naming spec

| Item | Old | New |
|---|---|---|
| Displayed app name | `security_cam` | `level1` |
| `applicationId` / namespace | `io.securitycam.security_cam` | `io.securitycam.level2` |
| Kotlin source tree | `…/kotlin/io/securitycam/security_cam/` | `…/kotlin/io/securitycam/level1/` |
| Project dir | `security_cam/` | `level1/` |
| MediaStore clips folder | `Movies/SecurityCam/` | `Movies/level1/` |
| FileProvider authority | `${applicationId}.fileprovider` | follows new id automatically |
| Channel names | `io.securitycam.security_cam/camera|frames|mic_pcm|preview_status` | deleted (no Dart bridge) |
| Test script PKG | `io.securitycam.security_cam` | `io.securitycam.level2` |
| Version | `1.0.1+2` | reset (e.g. `1.0.0+1`) — new app identity |

**Consequences accepted:** new `applicationId` ⇒ devices treat it as a brand-new app;
existing `events.db`, settings, Keystore secrets, and `Movies/SecurityCam` clips are
orphaned (no migration path; fresh start). Optional future: a one-time
export/import from the old id (not in scope).

## Dev-loop changes

"Delete Flutter entirely" removes the instant Linux-desktop camera/audio iteration.
Post-migration loop:

1. **JVM unit tests** (JUnit 5 + Robolectric where Android APIs are touched) — fast,
   host-side; the primary iteration path for detectors/pipeline/channels/storage.
2. **Android instrumentation tests** (`connectedAndroidTest`) on `pixel_34_aosp`
   (default) and `pixel_24_aosp` (min-API baseline) — the on-device suite,
   host-driven via `tool/run_android_integration_tests.sh` (`pm grant` +
   `[itest]` screen-off markers, reworked from Flutter).
3. **Emulator discipline stays** (AGENTS.md): one AVD at a time, ≥4 GiB free RAM,
   loadavg < ~75% cores, kill emulator/qemu after tests.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| YOLO w8a32 requires LiteRT Next `CompiledModel` | Phase 3 load spike first; fallback re-export to classic TFLite + `Interpreter`; CPU accelerator acceptable |
| BlazeFace model packaging (in plugin) | Extract `.tflite` early; MediaPipe Tasks `FaceDetector` preferred path |
| SMTP from Android | Raw socket (STARTTLS/SSL) vs jakarta.mail — decide via real-provider test in Phase 4 |
| Rotation edge cases (front mirror, TextureView vs SurfaceView modes) | `ImplementationMode.COMPATIBLE` (TextureView) + `DisplayListener`; verify on both AVDs |
| Package rename touches everything | Phase 7 sweep gated by `rg -i security_cam` returning nothing (except git history); test-script constants must not regress |
| Port drift (Dart logic ≠ Kotlin logic) | Port-test parity matrix (Phase 0.5); each ported unit test file has a JVM twin; `CompiledModel`/real-model gates reused |
| Emulator constraints | AGENTS.md resource gates are the primary loop; no parallel AVDs |
| Scope (7.3k lines Dart + 4 screens + inference) | Feature-frozen during port; media half already native; phases deliver working software independently |

## Verification / acceptance

- **Parity matrix** (`docs/plans/2026-08-20-native-kotlin-migration-plan.md`, Phase 0):
  every Dart test file → JVM twin; every integration scenario → instrumentation test.
- **On-device suite (Phase 7):** monitoring smoke, screen-off frame gate, on-device
  face, on-device person (real models), clip-with-audio, regions overlay + editor.
- **Rotation acceptance:** portrait + landscape + sensor, front and back camera,
  region overlay lands on the same scene points as the analysis truth.
- **Zoom acceptance:** pinch 1×→max within `ZoomState` range, animated, overlay
  remains aligned.
- **Rename acceptance:** `rg -i security_cam` clean (except git history); app
  installs as `level1` under `io.securitycam.level2`; clips land in `Movies/level1`.

## Phases (overview — detail in the implementation plan)

| Phase | Scope |
|---|---|
| 0 | Commit WIP; rename dir; scaffold native Gradle+Compose app; parity matrix |
| 1 | Media core reuse; Compose Monitor screen; rotation + zoom; state machine; screen-off smoke |
| 2 | Pipeline, detectors, regions, event pipeline, trigger batcher (pure-logic port) |
| 3 | Inference: YAMNet, YOLO26n, face; audio pipeline |
| 4 | Channels + storage (DataStore/Keystore/Room); event wiring + clip export |
| 5 | Full Compose UI: Settings, Region editor, Events, Monitor final |
| 6 | Drafted features natively: tamper, watchdog, schedule, gallery, sendTest UI |
| 7 | Test cutover; delete Flutter; rename sweep; AGENTS.md rewrite; final AVD verification |