# On-Device Tests for Existing Features — Design

Date: 2026-08-19
Status: Draft (implementation plan follows)

## Goal

Extend the Android on-device integration suite from "monitoring does not crash" to
**real, deterministic verification of the shipped detection features**: face
detection, person detection, audio detection (YAMNet), inclusion-region gating, and
channel delivery status — none of which is meaningfully asserted on-device today.
Determinism comes from feeding the real native models real inputs (bundled images
and WAV clips) instead of relying on the emulator's moving virtual camera.

## Current state (verified from code, 2026-08-19)

- **Existing suite** (`integration_test/`):
  - `monitoring_on_device_test.dart` — permission gate; one full monitoring run
    (motion event → snapshot + clip + audio + delete); **face/person are
    crash-gate only** (lines 262-322: run for a window, assert still monitoring —
    no detection is asserted).
  - `screen_off_gate_test.dart` — screen-off continuity (recordVideo=false).
  - `face_detection_linux_test.dart` / `person_detection_linux_test.dart` — the
    **real** `TfliteFaceEngine` / `YoloPersonEngine` against bundled images, but
    only on `-d linux`; never on Android.
  - Bundled image assets (`integration_test/assets/`): `messi5.jpg`, `astronaut.png`,
    `camera.png` (OpenCV BSD-3 / scikit-image public-domain).
- **Real audio model runs but is never asserted.** `buildAudioClassifier`
  (`lib/sensors/audio_classifier_factory.dart`) loads `YamnetAudioEventClassifier`
  on Android; logcat shows "YAMNet ready" and per-window scores during monitoring,
  but no test asserts a trigger. Emulators have no microphone stimulus.
- **Camera is not injectable.** `MonitorController.start()` calls
  `buildCameraSession(settings)` directly (`lib/state/monitor_controller.dart:156`);
  on Android that is always the native `AndroidCameraSession`. No way to feed
  deterministic frames into the pipeline on-device.
- **Harness is inline.** `DeviceHarness` (`monitoring_on_device_test.dart:29-121`)
  — shared setup (sqflite-ffi, `SettingsStore`, temp `FileSnapshotStore`,
  `PlatformVideoStore`, controller, `waitForEvent`) lives inside one test file.
- **Channel delivery status is recorded but unasserted.** `EventPipeline`
  (`lib/event/event_pipeline.dart:44-84`) records `channelStatuses` per row
  (`'delivered'`/`'failed'`); nothing on-device asserts it.
- **Runner** — `tool/run_android_integration_tests.sh` takes a single test file
  (arg 2), waits for boot, pre-grants permissions, coordinates the `[itest]`
  screen-off markers; harmless when a test file emits no markers.

## Design

### 1. Enabler: injectable camera builder

`MonitorController` gains an optional constructor param
`CameraSession Function(AppSettings)? cameraBuilder` (default =
`buildCameraSession`); `start()` (line 156) uses `(cameraBuilder ?? buildCameraSession)(settings)`.
Additive, existing behavior unchanged, no import changes in prod callers.

### 2. Enabler: `ReplayCameraSession` (pure Dart, test-only)

`integration_test/replay_camera_session.dart`:

- `ReplayCameraSession({required List<AnalysisFrame> frames, required Duration
  frameInterval, ColorBitmap? snapshotFrame})` implements `CameraSession`:
  - `init()` resets an index; a `Timer.periodic` pushes the next frame onto a
    broadcast `analysisFrames` (loops; frame `timestamp` = now, so cooldown/merging
    behave normally).
  - `takeSnapshot()` returns the current frame as a JPEG `Snapshot`.
  - `dispose()` cancels the timer.
- Frames are built by the test from bundled images (downscaled to the analysis
  resolution, gray+color planes via the same `image` package helpers the Linux
  tests use).
- **Consequence:** with a replay camera no native FGS/CameraX runs → the native
  pre-roll ring buffer is absent, so `exportClip` returns null. Replay E2E runs
  with `recordVideo=false` and asserts **no clip**; clip recording/export/audio
  stays covered by the existing real-camera test.

### 3. Enabler: shared harness + audio assets

- Extract `DeviceHarness` from `monitoring_on_device_test.dart` into
  `integration_test/device_harness.dart` (behavior-neutral; the existing file
  imports it). Extend it to accept an optional `cameraBuilder`.
- Add a minimal **16-bit PCM WAV reader** (pure Dart, ~30 lines; no new package) +
  bundled clips `integration_test/assets/audio/baby_cry.wav`,
  `glass_break.wav` (public-domain sources, 16 kHz mono), decoded into
  `AudioWindow`s for YAMNet.

### 4. New test: `direct_detection_on_device_test.dart`

Deterministic, real native models, no permissions/UI (constructs engines directly):

- **YAMNet scores:** load `YamnetAudioEventClassifier`; classify the bundled
  baby-cry WAV → `'baby_cry'` ≥ threshold; glass WAV → `'glass'` ≥ threshold;
  a silence window → all keys ≈ 0. A **score-validation step** first logs the raw
  scores so thresholds can be pinned (synthetic `SimulatedAudioSource.generateWindow`
  windows are the fallback if bundled-clip scores prove insufficient).
- **Face engine:** `TfliteFaceEngine` — blank frame → 0 faces; each bundled image
  → ≥ 1 face box (mirrors the Linux test, now on Android).
- **Person engine:** `YoloPersonEngine` — blank → 0; person images → ≥ 1 box.
- Scoped load/dispose per engine; generous per-test timeouts for swiftshader model
  load.

### 5. New test: `replay_monitoring_on_device_test.dart`

Deterministic E2E through the real pipeline (recordVideo=false):

- `ReplayCameraSession` replays a looping sequence (person image → face image →
  moving-rect frames) at 4 fps.
- Start monitoring via the UI; assert **motion + face + person event rows** arrive
  (first on-device assertions that face/person detectors fire), snapshots written
  to the temp store, and `channelStatuses['log'] == 'delivered'` on a row.
- **Region gating:** with a person image loop, configure an inclusion region
  covering the subject → triggers fire; a second harness/run with a region away
  from the subject → no trigger. First on-device proof of region semantics.
- No clip assertions (enabler §2). Buffered-pipeline behaviour is exercised as part
  of the run.

### 6. New test: env-gated webhook echo (optional)

- If `LIVE_ECHO_WEBHOOK_URL` is set: save a webhook `ChannelConfig` pointing at an
  echo server, trigger a motion event, assert `channelStatuses['echo'] ==
  'delivered'`. Self-skips via `markTestSkipped` otherwise. Full provider delivery
  (Telegram/Discord/SMTP/ntfy/Pushover) stays in the deferred
  `2026-08-19-live-channel-delivery-testing-plan`.

### 7. Runner + execution

- Extend `tool/run_android_integration_tests.sh` to accept **multiple** test files
  (space-separated) or add `tool/run_all_on_device.sh` running the files
  sequentially through the existing permission-granting path.
- Execute on `pixel_24_aosp` (min-API baseline; YAMNet runs on bionic API 24).
  `pixel_34_aosp` only if an API-34-specific assertion arises (none expected).
- AGENTS.md conventions: host RAM ≥ 4 GiB free / load < 75% of cores; headless AOSP
  image; one emulator at a time; cleanup (`adb emu kill` + `pkill -9 -f qemu-system`)
  and verify nothing lingers.

## Verification

- Each new file passes on `pixel_24_aosp` via the runner.
- Existing `monitoring_on_device_test.dart` + `screen_off_gate_test.dart` still
  pass after the harness extraction (behavior-neutral).
- Full Linux suite + `flutter analyze` green (refactors are additive).
- Emulator cleaned up afterwards.

## Deferred / not in this phase

- Real provider delivery (Telegram/Discord/SMTP/ntfy/Pushover) — deferred
  live-channel plan.
- Deterministic on-device tests for the *future* feature workstreams (privacy
  zones, tamper, health watchdog, schedule) — these extend this harness later.
- Deterministic on-device clip-recording tests via a fake camera (requires a fake
  native recording backend; real-camera test covers the clip path).

## Risks

- **YAMNet scores on bundled/synthetic audio may be low** → score-validation step
  pins thresholds; WAV clips are the primary stimulus, synthetic windows the
  fallback; the test asserts ≥ threshold, not absolute triggers.
- **Swiftshader latency** (model load + detection) → scoped load/dispose per engine
  and generous poll timeouts, consistent with the existing suite's 3-6 min
  windows.
- **Replay determinism** depends on 4 fps frame timing; frame `timestamp = now`
  keeps cooldown/merge behavior realistic; generous timeouts absorb jitter.
- **Refactor risk** is minimal: the camera builder is additive and defaults to the
  existing factory; harness extraction is guarded by the existing on-device tests.