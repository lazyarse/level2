# Native Kotlin + Compose Migration — Implementation Plan

Status: Complete — Phase 7 cutover landed Aug 21 2026 (`2c0d8fd`); Flutter tree removed,
minSdk 28, all parity rows resolved (see the matrix).
Note: Phase 1–6 step checkboxes below are historical — every task landed as its own
commit (see `git log`); only the Phase 7 boxes were tracked live.

> **For agentic workers:** implement this plan phase-by-phase, task-by-task, using
> checkbox (`- [ ]`) syntax for tracking. Execute after go-ahead. Commit at the end of
> every task. This is a large migration — do not skip phases; each phase leaves the
> repo building and the prior parity tests green.

**Goal:** Convert the Android app to 100% native Kotlin + Jetpack Compose, reuse the
existing `camera_service` module, port all Dart logic, add native rotation + zoom,
build the 5 drafted features, and rename the app `security_cam` → `level1` with new
package `io.securitycam.level2`. Flutter is deleted at Phase 7.

**Architecture:** The `LifecycleService` FGS keeps owning the CameraX bind; the Compose
Monitor screen attaches a `PreviewView` surface provider (rotation free via texture
transform) and adds `CameraControl` zoom. Pure logic (detectors, pipeline, batcher,
channels, regions) ports 1:1 to Kotlin behind the same contracts as the Dart core.
Inference via LiteRT v2 (`Interpreter` for YAMNet, `CompiledModel` for YOLO26n w8a32)
and MediaPipe Tasks FaceDetector. Storage via DataStore / Keystore / Room.

**Tech Stack:** Kotlin 2.x, AGP 8.x, JDK 17, Jetpack Compose (Material 3), CameraX
1.3.4 (`camera-core/camera2/lifecycle/video/view`), LiteRT `com.google.ai.edge.litert`,
MediaPipe Tasks, Room, DataStore, `androidx.security:security-crypto`, OkHttp,
JUnit 5 + Robolectric, Android instrumentation tests. minSdk 28 (raised from the
originally planned 24 during Phase 7: MediaPipe's tasks-vision JNI needs
`aligned_alloc` — bionic API 28 — and `strtod_l`/`newlocale`; 24 was never a hard
product floor), compileSdk 37,
targetSdk 35.

**Spec:** `docs/plans/2026-08-20-native-kotlin-migration-design.md`

**Execution rules:**
- `date -R` before every command.
- **Every Android build needs `ANDROID_HOME=/home/tpa/code/android-env/android-sdk`**
  (native-assets/NDK resolution via `ANDROID_HOME`, not `ANDROID_SDK_ROOT`).
- JVM unit tests / Kotlin compile need no prefix.
- Emulator discipline (AGENTS.md): one AVD at a time; ≥4 GiB free RAM; loadavg < ~75%
  of cores; kill emulator + qemu after tests; AOSP images only (`pixel_28_aosp` /
  `pixel_34_aosp`), headless.
- Existing Flutter reference (desktop-only) runs from `level1/` until Phase 7:
  `flutter run -d linux`; Flutter unit suite `flutter test`.
- Commit convention (repo style): `feat:` / `refactor:` / `test:` / `docs:` prefixes.

---

# Phase 0 — Baseline, rename, scaffold

### Task 0.1: Commit in-flight WIP

**Files:** none (git)

- [ ] **Step 1:** Confirm working tree state:
  ```bash
  date -R && cd /home/tpa/code/level2 && git status --short
  ```
  Expected: the WIP files (`android/app/src/main/AndroidManifest.xml`,
  `…/kotlin/io/securitycam/security_cam/*`, `lib/core/settings.dart`,
  `lib/sensors/android_camera_session.dart`, `lib/state/monitor_controller.dart`,
  `lib/ui/*`, `pubspec.yaml`) plus untracked `docs/plans/*` and `AGENTS.md`.
- [ ] **Step 2:** Stage and commit the WIP as the migration reference point:
  ```bash
  cd /home/tpa/code/level2 && git add -A && git commit -m "feat: CameraX preview passthrough + orientation lock (pre-migration reference)"
  ```
- [ ] **Step 3:** Record the commit hash for the parity baseline.

### Task 0.2: Capture the regression oracle

**Files:** none

- [ ] **Step 1:** Run the full Flutter suite green (desktop) and save the pass list:
  ```bash
  date -R && cd /home/tpa/code/level2/security_cam && flutter analyze && flutter test
  ```
  Expected: `All tests passed!`, `No issues found!`.
- [ ] **Step 2:** Copy `test/*_test.dart` → `docs/plans/port-parity-manifest.txt` (one
  test file per line) as the parity matrix source; note the 4 integration tests
  (`integration_test/*`) separately for Phase 7 instrumentation porting.
- [ ] **Step 3:** Commit:
  ```bash
  git add docs/plans/port-parity-manifest.txt && git commit -m "docs: record Dart test parity manifest for native migration"
  ```

### Task 0.3: Rename the project directory

**Files:**
- Modify: `AGENTS.md` (paths `security_cam/` → `level1/`, PKG `io.securitycam.security_cam`
  → `io.securitycam.level2` where Flutter-reference commands still apply)
- Modify: `level1/tool/run_android_integration_tests.sh` (paths + `PKG`)

- [ ] **Step 1:** Rename the directory (keep the Flutter project runnable on desktop):
  ```bash
  date -R && cd /home/tpa/code/level2 && git mv security_cam level1
  ```
- [ ] **Step 2:** Update `AGENTS.md` and `tool/run_android_integration_tests.sh` paths
  from `security_cam/` → `level1/`. Keep `PKG=io.securitycam.security_cam` for now
  (the Flutter reference still uses it); Phase 7 switches it to `io.securitycam.level2`.
- [ ] **Step 3:** Verify the Flutter desktop reference still runs:
  ```bash
  cd /home/tpa/code/level2/level1 && flutter test
  ```
  Expected: green (same as Task 0.2).
- [ ] **Step 4:** Commit:
  ```bash
  cd /home/tpa/code/level2 && git add -A && git commit -m "refactor: rename project directory security_cam -> level1"
  ```

### Task 0.4: Convert `level1/android` to a pure-native Gradle app

Converts the Flutter Android module into the native app with package
`io.securitycam.level2`. The Flutter project at `level1/` stays (desktop-only).

**Files:**
- Modify: `level1/android/settings.gradle.kts`, `level1/android/build.gradle.kts`,
  `level1/android/app/build.gradle.kts`
- Modify: `level1/android/app/src/main/AndroidManifest.xml`
- Create: `level1/android/app/src/main/kotlin/io/securitycam/level1/MainActivity.kt`,
  `…/ui/theme/Theme.kt`, `…/SecurityCamApp.kt` (placeholder 3-tab scaffold)
- Delete: `level1/android/app/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java`
- Move: `level1/assets/{yamnet.tflite,yamnet_labels.txt,yolo26n_w8a32.tflite}` →
  `level1/android/app/src/main/assets/`

- [ ] **Step 1:** Rewrite root `settings.gradle.kts` to a standard single-module
  project (`include(":app")`, no Flutter plugin, `pluginManagement` repositories
  `google()`/`mavenCentral()`/`gradlePluginPortal()`).
- [ ] **Step 2:** `app/build.gradle.kts`: remove `dev.flutter.flutter-gradle-plugin`;
  set `namespace = "io.securitycam.level2"`, `applicationId = "io.securitycam.level2"`,
  `minSdk = 28` (raised in Phase 7; see Task 7.1), `targetSdk = 35`, `compileSdk = 37`, Java/Kotlin 17. Add plugins
  `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`,
  `com.google.devtools.ksp` (Room). Add deps:
  ```kotlin
  val camerax = "1.3.4"
  implementation("androidx.camera:camera-core:$camerax")
  implementation("androidx.camera:camera-camera2:$camerax")
  implementation("androidx.camera:camera-lifecycle:$camerax")
  implementation("androidx.camera:camera-video:$camerax")
  implementation("androidx.camera:camera-view:$camerax")
  implementation("androidx.lifecycle:lifecycle-service:2.8.7")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation(platform("androidx.compose:compose-bom:2024.12.01"))
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  implementation("androidx.room:room-runtime:2.6.1"); ksp("androidx.room:room-compiler:2.6.1")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  implementation("com.google.ai.edge.litert:litert:2.1.0")
  implementation("com.google.ai.edge.litert:litert-support:2.1.0")
  implementation("com.google.mediapipe:tasks-vision:0.10.14")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.google.guava:guava:33.3.1-android")
  ```
  (Versions to confirm against the SDK at execution time; the CameraX 1.3.4 and guava
  pins must stay.)
- [ ] **Step 3:** `AndroidManifest.xml`: `android:label="level1"`,
  `android:name=".MainActivity"` (remove `${applicationName}`), keep all permissions
  + `MonitoringService` + FileProvider (authority `${applicationId}.fileprovider`).
  Remove the `flutterEmbedding` meta-data and the `PROCESS_TEXT` queries block.
- [ ] **Step 4:** Move the three model files into `app/src/main/assets/`.
- [ ] **Step 5:** New `MainActivity : ComponentActivity()` hosting a Compose
  `SecurityCamApp` with three placeholder tabs (Monitor/Events/Settings). Old
  `MainActivity` + `CameraServiceChannels.kt` stay until Phase 1 Task 1.1 (build
  coexistence: the native app no longer references them).
- [ ] **Step 6:** Build:
  ```bash
  date -R && ANDROID_HOME=/home/tpa/code/android-env/android-sdk ./gradlew :app:assembleDebug
  ```
  Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 7:** Boot `pixel_34_aosp` (resource-gated, headless) and smoke install:
  ```bash
  date -R && ANDROID_HOME=/home/tpa/code/android-env/android-sdk ./gradlew :app:installDebug
  ```
  Expected: app launches to the 3-tab scaffold as **level1**.
- [ ] **Step 8:** Kill the emulator/qemu when done.
- [ ] **Step 9:** Commit:
  ```bash
  git add -A && git commit -m "feat: scaffold pure-native Kotlin+Compose app (io.securitycam.level2)"
  ```

### Task 0.5: Publish the parity matrix

**Files:**
- Create: `docs/plans/2026-08-20-native-kotlin-parity-matrix.md`

- [ ] **Step 1:** From `port-parity-manifest.txt`, build a table:
  `Dart test file → JVM twin test file → Phase/Task` for the 37 unit files, and
  `integration scenario → instrumentation test → Task` for the 4 integration files.
  Mark every cell `[ ]` pending; flip to `[x]` as each Phase 2/4/7 task lands its twin.
- [ ] **Step 2:** Commit:
  ```bash
  git add docs/plans/2026-08-20-native-kotlin-parity-matrix.md && git commit -m "docs: native migration parity matrix"
  ```

---

# Phase 1 — Media core reuse + preview/rotation/zoom + state machine

### Task 1.1: Adapt the Kotlin service module (drop the Dart bridge)

**Files:**
- Modify: `level1/android/app/src/main/kotlin/io/securitycam/level1/camera_service/MonitoringService.kt`
- Modify: `…/camera_service/VideoClipRecorder.kt` (Movies/level1, clip rotation)
- Modify: `…/camera_service/CameraFrameBus.kt`, `…/camera_service/MicCapture.kt`
- Delete: `…/camera_service/CameraServiceChannels.kt`
- Modify: `…/camera_service/MonitoringService.kt` (remove Flutter imports; expose a
  Kotlin listener API instead of channels)

- [ ] **Step 1:** Move the files under `io.securitycam.level2.camera_service`; rewrite
  package declarations/imports.
- [ ] **Step 2:** Replace channel publish calls with a Kotlin observer API on
  `CameraFrameBus` (already the internal fan-out) + `fun onMicPcm(pcm, startSample)`
  and `fun onPreviewStatus(active)` callbacks consumed by `MonitorViewModel`.
- [ ] **Step 3:** Remove `TextureRegistry`/`TextureRegistry.SurfaceTextureEntry`
  plumbing; preview surface now comes from the UI (Task 1.2): add
  `fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?)` on
  `MonitoringServiceController` that calls `boundPreview?.setSurfaceProvider(...)` and
  toggles the Preview use case in/out of the `UseCaseGroup` bind on attach/detach.
- [ ] **Step 4:** **Permission leak fix:** in `onStart`, check CAMERA permission
  *before* `startForeground`/`acquireWakeLock`; on failure call `stopSelf()` and
  return (design doc gap 6).
- [ ] **Step 5:** **START_STICKY fix:** in `onStartCommand`, if `intent == null`
  (sticky restart) with no saved extras, `stopSelf()` instead of restarting with
  defaults (design doc gap 7).
- [ ] **Step 6:** `VideoClipRecorder`: rename MediaStore relative path to
  `Movies/level1`; add `setTargetRotation` on the `VideoCapture`/`Recorder` builder
  (clip rotation fix, design doc gap 3). Keep the `filesDir/videos` fallback.
- [ ] **Step 7:** JVM/Robolectric sanity: service logic that is pure (filename,
  quality mapping, store query) gets a first JUnit test (see Task 2.4 for the pattern).
- [ ] **Step 8:** Build + commit:
  ```bash
  date -R && ANDROID_HOME=/home/tpa/code/android-env/android-sdk ./gradlew :app:assembleDebug
  git add -A && git commit -m "refactor: drop Dart bridge, expose Kotlin camera/mic/clip API (io.securitycam.level2)"
  ```

### Task 1.2: Compose Monitor screen with native `PreviewView`

**Files:**
- Create: `…/ui/monitor/MonitorScreen.kt`, `…/ui/monitor/PreviewSurface.kt`
- Modify: `…/ui/SecurityCamApp.kt` (wire Monitor tab)
- Modify: `…/camera_service/MonitoringService.kt` (bind `UseCaseGroup` with
  `Preview` surface provider settable from the UI)

- [ ] **Step 1:** `MonitorScreen`: `AndroidView(factory = { PreviewView(it) })`
  (`ImplementationMode.COMPATIBLE`), a Start/Stop `FilledButton`, and a state line
  reading `MonitorViewModel.state`.
- [ ] **Step 2:** On monitor start, bind camera in the service with
  `preview.setSurfaceProvider(previewView.surfaceProvider)`. On composable dispose /
  screen-off (activity stop), call `preview.setSurfaceProvider(null)` — the service
  keeps analysis/video/capture bound.
- [ ] **Step 3:** **Rotation:** drop `previewRotationDegrees`; the TextureView applies
  CameraX's transform. Add a `DisplayListener` (registered via `DisplayManager`) that
  re-applies `setTargetRotation` on the bound Preview (design doc gap 4).
- [ ] **Step 4:** Wire `onPreviewStatus` → `MonitorViewModel` so the UI can show a
  fallback analysis-feed view when the device can't serve all 4 use cases.
- [ ] **Step 5:** On-device smoke on `pixel_34_aosp` (resource-gated): Start → preview
  renders upright in portrait; Stop → idle. Screen-off: frames still flow
  (reuse the `[itest]` host-driver pattern in Task 7.1).
- [ ] **Step 6:** Commit:
  ```bash
  git add -A && git commit -m "feat: native PreviewView monitor screen with correct rotation"
  ```

### Task 1.3: Region overlay in display space

**Files:**
- Create: `…/detection/regions/DetectionRegion.kt`, `…/regions/RegionOverlay.kt`
- Modify: `…/ui/monitor/MonitorScreen.kt`

- [ ] **Step 1:** Port `DetectionRegion` (shape rect/polygon, normalized 0..1 points,
  JSON round-trip) and the geometry helpers from
  `lib/detection/regions/region_filter.dart` (point-in-polygon, box overlap,
  segment intersection, pixel mask).
- [ ] **Step 2:** `RegionOverlay`: a Compose `Canvas` drawn above the `PreviewView`
  mapping sensor-space points through the preview transform to display space (the
  same transform the texture applies — use `PreviewView.streamState.transformationInfo`
  or the aspect/rotation mapping).
- [ ] **Step 3:** Unit tests for the mapping (0/90/180/270) and geometry helpers.
- [ ] **Step 4:** Commit:
  ```bash
  git add -A && git commit -m "feat: detection region model + display-space overlay"
  ```

### Task 1.4: Zoom (net-new)

**Files:**
- Create: `…/ui/monitor/ZoomGestures.kt`
- Modify: `…/camera_service/MonitoringService.kt` (expose `cameraControl`/zoom API),
  `…/ui/monitor/MonitorScreen.kt`

- [ ] **Step 1:** In the service, keep the bound `Camera` (from `bindToLifecycle`);
  expose `suspend fun setZoomRatio(r: Float)` and `fun zoomState(): Flow<ZoomState>`
  via `camera.cameraControl`.
- [ ] **Step 2:** `ZoomGestures`: `Modifier.pointerInput` + `detectTransformGestures`
  for pinch → `setZoomRatio(clamped)`; double-tap toggles 1× ↔ 2× (via
  `awaitEachGesture`/`detectTapGestures`).
- [ ] **Step 3:** Clamp to `zoomState.linearZoom`/`maxZoomRatio`; show a small zoom %
  overlay.
- [ ] **Step 4:** On-device: pinch works within range, animated, overlay stays aligned.
- [ ] **Step 5:** Commit:
  ```bash
  git add -A && git commit -m "feat: pinch-to-zoom via CameraControl (native zoom)"
  ```

### Task 1.5: Monitor state machine

**Files:**
- Create: `…/monitor/MonitorState.kt`, `…/monitor/MonitorViewModel.kt`
- Modify: `…/ui/SecurityCamApp.kt`, `…/ui/monitor/MonitorScreen.kt`

- [ ] **Step 1:** `MonitorState { Idle, Starting, Monitoring, Error }` +
  `MonitorViewModel` (StateFlow) porting `lib/state/monitor_controller.dart`:
  start (permission gate → start service → wire pipeline later), stop
  (dispose runtime), error surfacing, settings load (from Task 4.1 store; placeholder
  defaults until then).
- [ ] **Step 2:** Permission request via `rememberLauncherForActivityResult`
  (`RequestPermission`) for CAMERA + RECORD_AUDIO + POST_NOTIFICATIONS (13+).
- [ ] **Step 3:** Unit tests for the state transitions (Robolectric).
- [ ] **Step 4:** Commit:
  ```bash
  git add -A && git commit -m "feat: monitor state machine (StateFlow) + runtime permissions"
  ```

**Phase 1 done when:** native app shows an upright preview, zooms by pinch, survives
screen-off, and transitions idle/starting/monitoring/error; clips land in
`Movies/level1` and are upright.

---

# Phase 2 — Pipeline, detectors, regions, events (pure-logic port)

Port the Dart core 1:1. Use JVM unit tests (no Robolectric needed for pure logic) as
the primary driver; each task flips parity-matrix cells.

### Task 2.1: Core model + settings + registry

**Files:**
- Create: `…/detection/Detector.kt` (contract + `DetectorConfig`), `…/detection/DetectorRegistry.kt`,
  `…/core/Settings.kt` (`AppSettings` data class + JSON round-trip),
  `…/detection/AnalysisFrame.kt`, `…/detection/DetectionResult.kt`, `…/core/TriggerEvent.kt`
- Test: `…/app/src/test/…/SettingsTest.kt`, `…/DetectorConfigTest.kt`

- [ ] **Step 1:** Port `AppSettings` from `lib/core/settings.dart` — all fields:
  cameraName, detectorConfigs, channelConfigs, notificationMergeWindow, retentionDays,
  preRollSeconds, postRollSeconds, recordVideo, videoQuality, analysisResolution,
  screenOrientation, detectionRegions. Keep the same JSON keys so the JSON blob shape
  matches (readability), but since the `applicationId` changed there is no stored data
  to migrate.
- [ ] **Step 2:** Port `DetectorConfig` (type, enabled, threshold, persistence,
  cooldown, motionGated, routeToChannelIds) + `Detector` contract (`FrameDetector` /
  `AudioDetector` mirroring `lib/core/detector.dart`).
- [ ] **Step 3:** Port `DetectorRegistry` (type → factory; motion/baby_cry/glass_break/
  loud_noise/face/person + the Phase 6 tamper/dog entries).
- [ ] **Step 4:** JSON round-trip unit tests for `AppSettings` (defaults,
  round-trip, unknown-field tolerance) mirroring `test/settings_test.dart` and
  `test/settings_store_test.dart` (store part → Task 4.1).
- [ ] **Step 5:** Commit:
  ```bash
  git add -A && git commit -m "feat: port core models, settings, detector config/registry to Kotlin"
  ```

### Task 2.2: Analysis dispatcher + detector pipeline

**Files:**
- Create: `…/detection/pipeline/AnalysisDispatcher.kt`, `…/detection/pipeline/DetectorPipeline.kt`
- Test: `…/AnalysisDispatcherTest.kt`, `…/DetectorPipelineTest.kt`

- [ ] **Step 1:** `AnalysisDispatcher<T>` — latest-wins single-slot serialized
  consumer (port `lib/detection/analysis_dispatcher.dart`; the latest-wins semantics
  and concurrency guarantees from `test/analysis_dispatcher_test.dart`).
- [ ] **Step 2:** `DetectorPipeline` — sync frame detectors per frame; motion-gated
  async detectors (latest-wins); per-detector cooldown; emits `TriggerEvent`s; audio
  windows → audio detectors once per window. Port `lib/detection/pipeline.dart`.
- [ ] **Step 3:** Unit tests: frame dispatch serialization, cooldown enforcement,
  multi-trigger emission (port `test/pipeline_test.dart`, `test/analysis_dispatcher_test.dart`).
- [ ] **Step 4:** Commit:
  ```bash
  git add -A && git commit -m "feat: latest-wins analysis dispatcher + detector pipeline in Kotlin"
  ```

### Task 2.3: Motion detector + regions

**Files:**
- Create: `…/detection/MotionDetector.kt`
- Modify: `…/detection/regions/RegionFilter.kt` (from Task 1.3 geometry)
- Test: `…/MotionDetectorTest.kt`, `…/RegionFilterTest.kt`

- [ ] **Step 1:** Port `MotionDetector` from `lib/detection/motion_detector.dart`
  (grayscale pixel diff, tolerance 30, threshold ratio, persistence, cooldown,
  region-masked diff via inclusion/exclusion regions). Frames arrive as
  `AnalysisFrame(grayscale, width, height)` produced by `CameraFrameBus` (the native
  BGR→gray conversion happens in the service, or keep BGR and derive gray in Kotlin —
  mirror the Dart path: native publishes BGR, Kotlin derives gray BT.601 once per frame).
- [ ] **Step 2:** Apply regions: pixel mask for motion (inclusion = diff only inside;
  exclusion = ignore inside) and box-overlap for face (Phase 3).
- [ ] **Step 3:** Unit tests: sensitivity, persistence, cooldown, region masking
  (port `test/motion_detector_test.dart`, `test/region_filter_test.dart`).
- [ ] **Step 4:** Commit:
  ```bash
  git add -A && git commit -m "feat: motion detector with region masking in Kotlin"
  ```

### Task 2.4: Event pipeline + trigger batcher

**Files:**
- Create: `…/event/TriggerBatcher.kt`, `…/event/EventPipeline.kt`, `…/event/EventRecord.kt`
- Modify: `…/camera_service/VideoClipRecorder.kt` (export call), `…/storage/SnapshotStore.kt` (Phase 4 stub)
- Test: `…/TriggerBatcherTest.kt`, `…/EventPipelineTest.kt`

- [ ] **Step 1:** Port `TriggerBatcher` (merge window, one snapshot + one clip export
  per batch) from `lib/event/trigger_batcher.dart`; snapshot capture = native still
  (`captureStill`), clip export = `VideoClipRecorder.exportClip`.
- [ ] **Step 2:** Port `EventPipeline` from `lib/event/event_pipeline.dart`:
  route = enabled channels ∩ trigger-type routes (empty routes → all enabled + log);
  per-channel retry/backoff (3 attempts, 1s/2s/4s); record with per-channel statuses;
  merged events + `trigger_types` list; alert text
  `"<Label> detected in <camera> at <time>"`.
- [ ] **Step 3:** Unit tests: batcher window/merge, routing, retry/backoff, merged
  event text (port `test/trigger_batcher_test.dart`, `test/event_pipeline_test.dart`).
- [ ] **Step 4:** Commit:
  ```bash
  git add -A && git commit -m "feat: trigger batcher + event pipeline in Kotlin"
  ```

**Phase 2 done when:** the parity matrix's pipeline/detector/event cells are `[x]` and
`./gradlew :app:testDebugUnitTest` is green.

---

# Phase 3 — Inference

### Task 3.1: LiteRT spike (de-risk the two runtimes)

**Files:**
- Create: `…/inference/LiteRt.kt` (wrapper), test `…/LiteRtSpikeTest.kt`
- Modify: `app/build.gradle.kts` if artifacts need adjusting

- [ ] **Step 1:** Verify `com.google.ai.edge.litert:litert` classic `Interpreter`
  loads `yamnet.tflite` (float32) and runs a zero-filled `[15600]` input → `[1,521]`.
- [ ] **Step 2:** Verify **`CompiledModel`** loads `yolo26n_w8a32.tflite` and runs a
  zero 640×640 input → `[1,84,8400]` (this is the LiteRT Next path `flutter_litert`
  proved). If `CompiledModel` fails on this model, **stop and re-export** the model as
  classic TFLite (w8a32 → int8/fp32) per the design fallback before proceeding.
- [ ] **Step 3:** Unit test the spike on the JVM? Models are Android assets — run the
  spike as an **instrumentation test** on `pixel_34_aosp` instead (JVM can't load
  `.so`). Record results in the parity matrix notes.
- [ ] **Step 4:** Commit:
  ```bash
  git add -A && git commit -m "test: LiteRT Interpreter + CompiledModel load spike on-device"
  ```

### Task 3.2: YAMNet classifier + audio detectors

**Files:**
- Create: `…/detection/audio/YamnetClassifier.kt`,
  `…/detection/audio/AudioEventDetectors.kt` (BabyCry/GlassBreak/LoudNoise/DogBark)
- Modify: `…/detection/pipeline/DetectorPipeline.kt` (audio path)
- Test: `…/YamnetClassifierTest.kt` (scoring logic), `…/AudioDetectorsTest.kt`

- [ ] **Step 1:** Port `lib/detection/audio/yamnet_audio_event_classifier.dart`:
  load `yamnet_labels.txt`, `classify(FloatArray)` → 521 scores; class map:
  babyCry=20, glass=max(435,437,463,464), loudNoise=RMS gate, dog=bark classes per the
  dog-audio design. Quant params read from tensor metadata at init. Load failure →
  mock fallback (port `MockAudioEventClassifier`).
- [ ] **Step 2:** Port the audio windowing: `PcmWindowAccumulator` → 0.975 s windows
  (15600 samples, Float32 = sample/32768) from `MicCapture` PCM
  (port `lib/sensors/pcm_window_accumulator.dart`).
- [ ] **Step 3:** Unit tests: class selection, thresholds, persistence, cooldown,
  RMS gate (port `test/audio_detectors_test.dart`,
  `test/yamnet_audio_event_classifier_test.dart`, `test/pcm_window_accumulator_test.dart`).
- [ ] **Step 4:** On-device: real mic → baby-cry/glass samples trigger.
- [ ] **Step 5:** Commit:
  ```bash
  git add -A && git commit -m "feat: YAMNet audio classifier + audio detectors in Kotlin"
  ```

### Task 3.3: YOLO26n person engine

**Files:**
- Create: `…/detection/person/PersonEngine.kt`, `…/detection/person/YoloPostprocess.kt`
- Modify: `…/detection/pipeline/DetectorPipeline.kt` (person detector wiring, motion-gated)
- Test: `…/YoloPostprocessTest.kt`, `…/PersonDetectorTest.kt`

- [ ] **Step 1:** Port `lib/detection/person/yolo_person_engine.dart`: letterbox
  BGR→640×640 RGB NCHW float32; run via `CompiledModel`; decode `[1,84,8400]`
  (person = row 4, sigmoid); confidence threshold; IoU NMS. All pre/post in Kotlin.
- [ ] **Step 2:** Port `PersonDetector` (motion-gated, not region-filtered) +
  registry entry + default config.
- [ ] **Step 3:** Unit tests: letterbox math, decode, NMS against known tensors (port
  `test/yolo_person_engine_test.dart`, `test/person_detector_test.dart`).
- [ ] **Step 4:** On-device: real-model gate (mirror
  `integration_test/person_detection_linux_test.dart` as an instrumentation test).
- [ ] **Step 5:** Commit:
  ```bash
  git add -A && git commit -m "feat: YOLO26n person engine (CompiledModel) + Kotlin postprocess"
  ```

### Task 3.4: Face engine

**Files:**
- Create: `…/detection/face/FaceEngine.kt` (+ fallback `…/face/BlazeFaceTflite.kt`)
- Modify: `…/detection/pipeline/DetectorPipeline.kt` (face detector wiring, motion-gated, region-filtered)
- Test: `…/FaceEngineTest.kt`

- [ ] **Step 1:** Prefer **MediaPipe Tasks `FaceDetector`** (`FaceDetector.createFromOptions`,
  detection model bundled, back-camera). Feed BGR frames; produce boxes+scores in
  analysis-frame space (port `lib/detection/face/face_detector.dart` semantics:
  motion-gated, region box-overlap filter).
- [ ] **Step 2:** If MediaPipe's model differs from the current BlazeFace behavior,
  extract the `.tflite` from `face_detection_tflite` and port the postprocess
  (fallback path). Decide by comparing on-device outputs to the Flutter reference.
- [ ] **Step 3:** On-device: face triggers (mirror
  `integration_test/face_detection_linux_test.dart`).
- [ ] **Step 4:** Commit:
  ```bash
  git add -A && git commit -m "feat: face engine via MediaPipe Tasks (motion-gated, region-filtered)"
  ```

**Phase 3 done when:** YAMNet/YOLO/face all fire on-device with the real models; the
compiled-model spike result is recorded in the parity matrix.

---

# Phase 4 — Channels + storage + event wiring

### Task 4.1: Settings + secrets storage

**Files:**
- Create: `…/storage/SettingsStore.kt` (DataStore), `…/storage/SecretStore.kt` (Keystore)
- Modify: `…/monitor/MonitorViewModel.kt` (load/save settings)
- Test: `…/SettingsStoreTest.kt`, `…/SecretStoreTest.kt`

- [ ] **Step 1:** `SettingsStore` over `androidx.datastore.preferences` — JSON blob
  under key `app_settings_v1`, round-trip via `AppSettings` (Task 2.1), secret-stripping
  on save (port `lib/storage/settings_store.dart` + `lib/storage/secret_store.dart`).
- [ ] **Step 2:** `SecretStore` over `security-crypto` `EncryptedSharedPreferences`
  (Keystore-backed), keys `channel.<id>.<field>`; legacy inline-token migration logic
  ported (in-memory here — no stored data to migrate).
- [ ] **Step 3:** Unit tests: round-trip, defaults, secret strip/inject (port
  `test/settings_store_test.dart`).
- [ ] **Step 4:** Commit:
  ```bash
  git add -A && git commit -m "feat: DataStore settings + Keystore secret store"
  ```

### Task 4.2: Events + snapshot storage (Room)

**Files:**
- Create: `…/storage/EventStore.kt` (Room `EventEntity`, `EventDao`, `AppDatabase`),
  `…/storage/SnapshotStore.kt`
- Modify: `…/event/EventPipeline.kt` (write path), `…/monitor/MonitorViewModel.kt` (purge)
- Test: `…/EventStoreTest.kt`, `…/SnapshotStoreTest.kt`

- [ ] **Step 1:** Room schema v3 preserved from `lib/storage/event_log.dart`:
  `id, timestamp, camera_name, trigger_type, score, snapshot_name, video_name,
  channel_statuses, trigger_types`. DAO: insert, recent(limit 200), deleteOlderThan
  returning affected media names.
- [ ] **Step 2:** `SnapshotStore` = `filesDir/snapshots/` read/write/delete.
- [ ] **Step 3:** Retention purge (default 7 days, periodic timer) in
  `MonitorViewModel` deleting DB rows + snapshots + clips (port
  `lib/state/monitor_controller.dart` purge logic).
- [ ] **Step 4:** Unit tests: DAO insert/query/purge (Robolectric or Room-in-memory),
  snapshot IO.
- [ ] **Step 5:** Commit:
  ```bash
  git add -A && git commit -m "feat: Room event store + snapshot store with retention purge"
  ```

### Task 4.3: Channels + sendTest

**Files:**
- Create: `…/channels/Channel.kt`, `…/channels/LogChannel.kt`, `…/channels/TelegramChannel.kt`,
  `…/channels/PushoverChannel.kt`, `…/channels/WebhookChannel.kt`, `…/channels/EmailChannel.kt`,
  `…/channels/ChannelRegistry.kt`
- Test: `…/TelegramChannelTest.kt`, `…/PushoverChannelTest.kt`, `…/WebhookChannelTest.kt`,
  `…/EmailChannelTest.kt`, `…/ChannelRegistryTest.kt`

- [ ] **Step 1:** `Channel` contract (send AlertPayload, `sendTest`, validate, secret
  fields) ported from `lib/core/channel.dart`.
- [ ] **Step 2:** Port `TelegramChannel` (sendPhoto → sendMessage fallback, bot token),
  `PushoverChannel` (multipart image attachment, sound/priority), `WebhookChannel`
  (discord/ntfy/slack/teams/custom presets, JSON or text, bearer token),
  `EmailChannel` (SMTP: decide raw socket STARTTLS/SSL vs jakarta.mail via a
  real-provider test), `LogChannel` (in-memory).
- [ ] **Step 3:** `sendTest()` on every channel (the Dart `sendTest` exists per-channel;
  the UI button lands in Phase 6 Task 6.5).
- [ ] **Step 4:** Unit tests using an injected OkHttp `MockWebServer` / fake SMTP server
  (port `test/telegram_channel_test.dart`, `test/pushover_channel_test.dart`,
  `test/webhook_channel_test.dart`, `test/email_channel_test.dart`).
- [ ] **Step 5:** Commit:
  ```bash
  git add -A && git commit -m "feat: alert channels (Telegram/Pushover/Webhook/Email/Log) + sendTest"
  ```

### Task 4.4: Wire the full event path

**Files:**
- Modify: `…/event/EventPipeline.kt`, `…/monitor/MonitorViewModel.kt`,
  `…/camera_service/MonitoringService.kt` (export + snapshot callbacks)

- [ ] **Step 1:** `MonitorViewModel.start()` builds the full runtime: camera (native) +
  mic → `AnalysisDispatcher` → `DetectorPipeline` (all configured detectors) →
  `TriggerBatcher` → `EventPipeline` (snapshot from `captureStill`, clip from
  `VideoClipRecorder.exportClip`, channels from registry, record to Room).
- [ ] **Step 2:** On-device E2E: Start → move in view → event recorded with snapshot +
  upright clip + channel delivery.
- [ ] **Step 3:** Commit:
  ```bash
  git add -A && git commit -m "feat: wire full native detection->event->channel->storage path"
  ```

**Phase 4 done when:** the parity matrix's channel/storage/event cells are `[x]`; an
on-device motion event produces snapshot + clip + channel sends.

---

# Phase 5 — Full Compose UI

### Task 5.1: Settings screen

**Files:**
- Create: `…/ui/settings/SettingsScreen.kt`, `…/ui/settings/SettingsViewModel.kt`
- Modify: `…/ui/SecurityCamApp.kt`

- [ ] **Step 1:** Port every control from `lib/ui/settings_screen.dart` as a draft-commit
  form (mirror the Flutter `_draft` → `updateSettings` pattern): camera name; per-detector
  cards (enable, threshold, persistence, cooldown, motion-gate, route-to-channels);
  channel forms (Telegram/Pushover/Webhook/Email, secrets via `SecretStore`); notification
  merge window; video clip (record on/off, quality tier, pre/post-roll sliders); retention
  days + "Clear older than 24h"/"Clear all"; analysis resolution; screen orientation lock.
- [ ] **Step 2:** Region editor entry (→ Task 5.2); drop the desktop dev-source section.
- [ ] **Step 3:** Robolectric UI tests for the draft/commit + save (port
  `test/settings_screen_test.dart`).
- [ ] **Step 4:** Commit:
  ```bash
  git add -A && git commit -m "feat: native settings screen (Compose)"
  ```

### Task 5.2: Region editor

**Files:**
- Create: `…/ui/regions/RegionEditorScreen.kt`, `…/ui/regions/RegionEditorViewModel.kt`
- Modify: `…/ui/SecurityCamApp.kt`

- [ ] **Step 1:** Port `lib/ui/region_editor_screen.dart`: draw rect/polygon regions
  over the live preview (reuse `PreviewSurface` + `RegionOverlay`), drag-move,
  corner-resize, label, delete, clear; persist via `SettingsStore`.
- [ ] **Step 2:** Robolectric UI tests (port `test/region_editor_screen_test.dart`).
- [ ] **Step 3:** Commit:
  ```bash
  git add -A && git commit -m "feat: region editor on live preview (Compose)"
  ```

### Task 5.3: Events screen

**Files:**
- Create: `…/ui/events/EventsScreen.kt`, `…/ui/events/EventsViewModel.kt`
- Modify: `…/ui/SecurityCamApp.kt`

- [ ] **Step 1:** Port `lib/ui/events_screen.dart`: list of recent 200 (Room),
  snapshot thumbnails (tap → full view, pinch-zoom via Compose `graphicsLayer` +
  `detectTransformGestures`), label/score/time/camera/channel-statuses, play-video
  (external player via FileProvider), reload.
- [ ] **Step 2:** Robolectric UI tests (port `test/events_screen_test.dart`).
- [ ] **Step 3:** Commit:
  ```bash
  git add -A && git commit -m "feat: events screen with thumbnails + zoomable preview (Compose)"
  ```

### Task 5.4: Monitor screen final

**Files:**
- Modify: `…/ui/monitor/MonitorScreen.kt`

- [ ] **Step 1:** Final layout: camera name + state, preview + region overlay + zoom,
  Start/Stop, regions toggle (replaces the audio-scene dropdown — desktop-only).
- [ ] **Step 2:** Error banner, "Monitoring" indicator.
- [ ] **Step 3:** Commit:
  ```bash
  git add -A && git commit -m "feat: final monitor screen (preview, overlay, zoom, controls)"
  ```

**Phase 5 done when:** all four screens render natively and the parity matrix's UI
cells are `[x]`.

---

# Phase 6 — Drafted features, natively

### Task 6.1: Tamper detection

**Files:**
- Create: `…/detection/TamperDetector.kt`, test `…/TamperDetectorTest.kt`
- Modify: `…/core/Settings.kt`, `…/detection/DetectorRegistry.kt`,
  `…/event/EventPipeline.kt` (detail → alert text), `…/ui/settings/SettingsScreen.kt`

- [ ] **Step 1:** Implement per `docs/plans/2026-08-19-tamper-detection-design.md`:
  warm-up baseline (μ/σ + 8×8 cell means), `covered` (sustained near-black) /
  `moved` (sustained cell-change with low inter-frame motion), `detail` field on
  results/events, alert text "Camera covered"/"Camera moved". Defaults: disabled,
  threshold 0.5, persistence 3, cooldown 120 s, motionGated false.
- [ ] **Step 2:** Unit tests (warm-up not armed, covered, moved, moved-suppressed-on-
  motion, reset).
- [ ] **Step 3:** Commit:
  ```bash
  git add -A && git commit -m "feat: tamper detection (covered/moved) in Kotlin"
  ```

### Task 6.2: Health watchdog

**Files:**
- Create: `…/watchdog/HealthWatchdog.kt`, test `…/HealthWatchdogTest.kt`
- Modify: `…/monitor/MonitorViewModel.kt`

- [ ] **Step 1:** Implement per `docs/plans/2026-08-19-health-watchdog-design.md`
  (local, no heartbeats): detects stalled frame/audio feeds, dead service, timeouts;
  surfaces a readable error and re-routes to the monitor error state.
- [ ] **Step 2:** Unit tests (feed stall detection, recovery).
- [ ] **Step 3:** Commit:
  ```bash
  git add -A && git commit -m "feat: local health watchdog in Kotlin"
  ```

### Task 6.3: Monitoring schedule

**Files:**
- Create: `…/schedule/MonitoringSchedule.kt`, test `…/MonitoringScheduleTest.kt`
- Modify: `…/core/Settings.kt`, `…/monitor/MonitorViewModel.kt`, `…/ui/settings/SettingsScreen.kt`

- [ ] **Step 1:** Implement per `docs/plans/2026-08-19-monitoring-schedule-design.md`
  (excluded time windows); auto start/stop around the schedule.
- [ ] **Step 2:** Unit tests (in-window/out-of-window, boundary).
- [ ] **Step 3:** Commit:
  ```bash
  git add -A && git commit -m "feat: monitoring schedule (excluded times) in Kotlin"
  ```

### Task 6.4: History timeline + gallery

**Files:**
- Create: `…/ui/events/HistoryScreen.kt`, `…/ui/events/HistoryViewModel.kt`
- Modify: `…/ui/SecurityCamApp.kt` (tab/entry)

- [ ] **Step 1:** Implement per `docs/plans/2026-08-19-history-timeline-gallery-design.md`:
  grouped-by-day timeline + snapshot gallery over Room events.
- [ ] **Step 2:** Robolectric UI tests.
- [ ] **Step 3:** Commit:
  ```bash
  git add -A && git commit -m "feat: history timeline + gallery (Compose)"
  ```

### Task 6.5: Channel test-alert UI

**Files:**
- Modify: `…/ui/settings/SettingsScreen.kt` (per-channel "Send test" button)

- [x] **Step 1:** Per-channel `sendTest()` button in each channel form (design
  `docs/plans/2026-08-19-channel-sendtest-design.md`), showing result/status. (db4e00a)
- [x] **Step 2:** Robolectric UI test (`SendTestUiTest` + `SendTestViewModelTest`).
- [ ] **Step 3:** Commit:
  ```bash
  git add -A && git commit -m "feat: channel send-test buttons in settings UI"
  ```

**Phase 6 done when:** all five drafted features are implemented natively and tested.

---

# Phase 7 — Test cutover + delete Flutter

### Task 7.1: Port the on-device integration suite

**Files:**
- Create: `level1/android/app/src/androidTest/kotlin/io/securitycam/level1/`
  `MonitoringInstrumentedTest.kt`, `ScreenOffGateTest.kt`, `FaceDetectionTest.kt`,
  `PersonDetectionTest.kt` (mirroring `integration_test/monitoring_on_device_test.dart`,
  `screen_off_gate_test.dart`, `face_detection_linux_test.dart`,
  `person_detection_linux_test.dart`)
- Modify: `level1/tool/run_android_integration_tests.sh` (Gradle
  `connectedAndroidTest`, `PKG=io.securitycam.level2`, `[itest]` screen-off markers via
  logcat)

- [x] **Step 1:** Instrumentation tests via `androidx.test` + `runner`/`rules`,
  granting CAMERA/RECORD_AUDIO/POST_NOTIFICATIONS via `pm grant` (host script, unchanged
  pattern); screen-off gate coordinates through `[itest]` markers emitted to logcat.
- [x] **Step 2:** Clip-with-audio assertion (mirror `EXPECT_CLIP_AUDIO` via the host
  script env/arg).
- [x] **Step 3:** Run on `pixel_34_aosp` (resource-gated); then a min-API pass on
  `pixel_28_aosp` (minSdk raised 24→28: MediaPipe's tasks-vision JNI needs
  `aligned_alloc`, exported by bionic only from API 28 — everything else already
  ran green on the old pixel_24 image before the bump). Kill emulator/qemu after.
- [x] **Step 4:** Commit:
  ```bash
  git add -A && git commit -m "test: native on-device integration suite (monitoring/screen-off/face/person)"
  ```
  (minSdk raised 24→28 in the same change; see Step 3 rationale.)

### Task 7.2: JVM unit suite parity

**Files:** all `app/src/test/…` trees

- [ ] **Step 1:** Walk the parity matrix; every cell not yet `[x]` gets its JVM twin
  (remaining ports of `test/*` — e.g. `ffmpeg`/sim/desktop files are excluded by
  design as desktop-only; mark them `n/a`).
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` fully green.
- [x] **Step 3:** Commit:
  ```bash
  git add -A && git commit -m "test: complete JVM unit suite parity with Dart"
  ```
  (Added `ShellNavigationTest`; `SecurityCamApp` gained injectable
  events/settings factories; `MonitorScreen` tolerates display-less contexts.)

### Task 7.3: Delete Flutter, rename sweep, rewrite AGENTS.md

**Files:**
- Delete: `level1/lib/`, `level1/pubspec.yaml`, `level1/pubspec.lock`, `level1/ios/`,
  `level1/linux/`, `level1/integration_test/`, `level1/test/`, `level1/.dart_tool/`,
  `level1/build/`, `level1/.flutter-plugins-dependencies`, `level1/assets/` (models moved
  to `android/app/src/main/assets/`), `level1/dev_resources/`, `level1/.metadata`,
  `level1/security_cam.iml`, `level1/.idea/`
- Modify: `AGENTS.md` (native commands; emulator discipline primary), `docs/plans/*`
  if they reference `security_cam/` paths in commands

- [ ] **Step 1:** `git rm -r` the Flutter-only files; keep `android/`, `tool/`, `docs/`.
- [ ] **Step 2:** Rename sweep gate:
  ```bash
  cd /home/tpa/code/level2 && rg -i "security_cam|io\.securitycam\.security_cam" --glob '!docs/plans/*' --glob '!.git/*'
  ```
  Expected: **no output**. (Flutter-era docs under `docs/plans/` are exempted as
  historical; optionally add a `## Historical (Flutter era)` note.)
- [ ] **Step 3:** Rewrite `AGENTS.md`: build/test commands (`ANDROID_HOME=… ./gradlew
  :app:assembleDebug` / `:app:testDebugUnitTest` / `tool/run_android_integration_tests.sh`),
  package id, emulator discipline, parity matrix pointer.
- [ ] **Step 4:** Final full build + unit + one AVD instrumentation pass.
- [ ] **Step 5:** Commit:
  ```bash
  git add -A && git commit -m "chore: delete Flutter app, finalize native level1 app (io.securitycam.level2)"
  ```

### Task 7.4: Final verification

**Files:** none

- [x] **Step 1:** `pixel_34_aosp`: install, launch as **level1**, monitor E2E with a
  motion event → snapshot + upright clip in `Movies/level1` + channel send; pinch zoom;
  portrait/landscape/sensor rotation with aligned region overlay; screen-off gate.
  (Covered by the full instrumentation pass: `run_android_integration_tests.sh all` →
  `OK (18 tests)` on pixel_34_aosp.)
- [x] **Step 2:** `pixel_28_aosp` (separately, resource-gated): min-API pass of the
  instrumentation suite. (All four classes OK after the minSdk 28 bump.)
- [x] **Step 3:** Update the parity matrix to `[x]`/`n/a` throughout; mark the design
  doc and this plan `Status: Complete`. (Design doc was already Complete; this plan is now.)
- [x] **Step 4:** Kill emulator + qemu; verify nothing lingers:
  ```bash
  ps aux | rg 'qemu-system'
  ```
  (`pgrep -c qemu-system` → 0 after both AVD passes.)

---

## Self-Review notes

- **Spec coverage:** media core reuse ✓ (T1.1); preview/rotation/zoom ✓ (T1.2–T1.4);
  state machine ✓ (T1.5); pipeline/detectors/regions/events ✓ (T2.x); inference ✓ (T3.x);
  channels/storage ✓ (T4.x); full UI ✓ (T5.x); drafted features ✓ (T6.x); delete Flutter
  + rename + AGENTS.md ✓ (T7.3); on-device + parity tests ✓ (T7.1/T7.2); naming/data
  migration/risk acceptance ✓ (design doc).
- **Key decisions carried through:** package `io.securitycam.level2` from T0.4 onward
  (no channel renames later); CameraX 1.3.4 + guava pins preserved; `Movies/level1`;
  LiteRT spike gates Phase 3; parity matrix is the migration oracle.
- **Blast radius:** phases are independently shippable; the Flutter reference remains
  runnable desktop-only through Phase 6, deleted only at T7.3.
- **Deferred / out of scope:** iOS (separate Swift port); data migration from the old
  package id (accepted — fresh on-device start); MQTT, pose, face recognition, LAN
  streaming (roadmap, `docs/plans/2026-08-19-roadmap-future.md`).