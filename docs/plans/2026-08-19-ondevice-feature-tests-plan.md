# On-Device Tests for Existing Features — Implementation Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [ ]`)
> syntax for tracking. Execute after go-ahead.

**Goal:** Deterministic on-device verification of shipped detection features (face,
person, audio/YAMNet, inclusion regions, channel delivery status) by feeding the
real native models real inputs, plus a runner and an `pixel_24_aosp` execution
pass.

**Architecture:** Injectable camera builder on `MonitorController` +
`ReplayCameraSession` (pure Dart) for deterministic E2E; direct engine invocations
for model-level tests; a shared `DeviceHarness`; bundled WAV clips + a minimal WAV
reader for YAMNet; env-gated webhook echo for real-HTTP delivery status.

**Spec:** `docs/plans/2026-08-19-ondevice-feature-tests-design.md`

**Execution rule:** Use `pixel_24_aosp` per AGENTS.md (host resource pre-check,
headless AOSP image, one emulator at a time, cleanup). Linux desktop iteration for
any non-native refactors. No `pixel_34_aosp` unless an API-34-specific assertion
emerges.

---

### Task 1: Enablers (controller injection, replay camera, harness, WAV assets)

**Files:**
- Modify: `security_cam/lib/state/monitor_controller.dart`
- Create: `security_cam/integration_test/replay_camera_session.dart`
- Create: `security_cam/integration_test/device_harness.dart`
- Modify: `security_cam/integration_test/monitoring_on_device_test.dart`
- Create: `security_cam/integration_test/wav_reader.dart`
- Create: `security_cam/integration_test/assets/audio/baby_cry.wav`
- Create: `security_cam/integration_test/assets/audio/glass_break.wav`

- [ ] **Step 1:** Add `final CameraSession Function(AppSettings)? cameraBuilder;`
  to `MonitorController`; in `start()` use
  `final camera = (cameraBuilder ?? buildCameraSession)(settings);`
  (`monitor_controller.dart:156`). No other behavior change.
- [ ] **Step 2:** `ReplayCameraSession` (design §2): `init` starts a `Timer.periodic`
  pushing looping frames onto a broadcast stream; `takeSnapshot` returns the
  current frame as JPEG; `dispose` cancels. Build frames from bundled images at the
  analysis resolution (gray + color planes).
- [ ] **Step 3:** Extract `DeviceHarness` from `monitoring_on_device_test.dart` into
  `device_harness.dart`; add an optional `cameraBuilder` param; update
  `monitoring_on_device_test.dart` to import it (behavior-neutral).
- [ ] **Step 4:** `wav_reader.dart` — minimal 16-bit PCM mono WAV → `Float32List`
  at 16 kHz; add the two audio assets.
- [ ] **Step 5:** Verify: `flutter test` + `flutter analyze` green on Linux; the
  existing on-device files still compile.
- [ ] **Step 6:** Commit:
  ```bash
  date -R && git add -A && git commit -m "test: on-device harness enablers (injectable camera, replay session, shared harness, WAV reader)"
  ```

### Task 2: `direct_detection_on_device_test.dart`

**Files:**
- Create: `security_cam/integration_test/direct_detection_on_device_test.dart`

- [ ] **Step 1:** YAMNet group — load `YamnetAudioEventClassifier`; first a
  **score-validation** test that logs `'baby_cry'`/`'glass'` scores for the WAVs
  and synthetic windows; pin thresholds from the observed values (assert ≥
  threshold, fall back to synthetic windows if WAV scores are too low). Then:
  baby-cry WAV → `baby_cry ≥ threshold`; glass WAV → `glass ≥ threshold`; silence
  → keys ≈ 0. Scoped load/dispose.
- [ ] **Step 2:** Face engine group — `TfliteFaceEngine`: blank → 0 faces; each
  bundled image (messi5/astronaut/camera) → ≥ 1 face box (port of the Linux test).
  Scoped load/dispose.
- [ ] **Step 3:** Person engine group — `YoloPersonEngine`: blank → 0; person
  images → ≥ 1 box. Scoped load/dispose.
- [ ] **Step 4:** Verify on device via the runner:
  ```bash
  date -R && ANDROID_HOME=/home/tpa/code/android-env/android-sdk \
    security_cam/tool/run_android_integration_tests.sh emulator-5554 \
    integration_test/direct_detection_on_device_test.dart
  ```
- [ ] **Step 5:** Commit:
  ```bash
  date -R && git add -A && git commit -m "test: deterministic on-device model tests (YAMNet scores, face, person)"
  ```

### Task 3: `replay_monitoring_on_device_test.dart`

**Files:**
- Create: `security_cam/integration_test/replay_monitoring_on_device_test.dart`

- [ ] **Step 1:** Replay E2E — harness with `cameraBuilder: () => replayCam`,
  `recordVideo=false` saved to settings; pump the app, start monitoring, assert:
  motion + face + person event rows arrive (via `waitForEvent`), snapshot files
  written, `channelStatuses['log'] == 'delivered'` on a row, and no `videoName`.
- [ ] **Step 2:** Region-gating test — second harness with an inclusion region
  covering the subject area → triggers fire; a region away from the subject → no
  trigger within the window. (Reuses `pixelMask`-shaped normalized coords matching
  the subject's position in the replayed frame.)
- [ ] **Step 3:** Verify on device via the runner; commit:
  ```bash
  date -R && git add -A && git commit -m "test: deterministic replay-camera E2E (detection, regions, log delivery)"
  ```

### Task 4: env-gated webhook echo

**Files:**
- Modify: `security_cam/integration_test/replay_monitoring_on_device_test.dart` (or a small separate file)

- [ ] **Step 1:** When `LIVE_ECHO_WEBHOOK_URL` is set, save a webhook `ChannelConfig`
  (preset custom) pointing at the echo server, trigger a motion event, assert
  `channelStatuses['echo'] == 'delivered'`; otherwise `markTestSkipped`.
- [ ] **Step 2:** Verify it self-skips without the env var; commit:
  ```bash
  date -R && git add -A && git commit -m "test: env-gated on-device webhook echo delivery"
  ```

### Task 5: Runner extension + full device pass + docs

**Files:**
- Modify: `security_cam/tool/run_android_integration_tests.sh`
- (Optional) Create: `security_cam/tool/run_all_on_device.sh`

- [ ] **Step 1:** Extend the runner to accept multiple test files (space-separated
  args) or add `run_all_on_device.sh` that runs, sequentially through the existing
  permission-granting path: `direct_detection_on_device_test.dart`,
  `replay_monitoring_on_device_test.dart`, `monitoring_on_device_test.dart`,
  `screen_off_gate_test.dart`.
- [ ] **Step 2:** Execute the full pass on `pixel_24_aosp` per AGENTS.md (resource
  pre-check, headless AOSP, one emulator, cleanup + verify nothing lingers).
- [ ] **Step 3:** Update the app design doc's §B9.4 on-device-verification note to
  reference this plan; commit docs + runner:
  ```bash
  date -R && git add -A && git commit -m "tool+docs: run full on-device feature suite on pixel_24_aosp"
  ```

---

## Self-Review notes

- **Spec coverage:** injectable camera ✓; replay camera ✓; shared harness ✓; WAV
  reader + assets ✓; direct YAMNet/face/person tests ✓; replay E2E with region
  gating + log delivery ✓; env-gated webhook echo ✓; runner + device pass ✓.
- **Key decisions:** determinism via real inputs (bundled images/WAVs) rather than
  the virtual camera; replay E2E runs `recordVideo=false` (no native ring buffer);
  clip path stays covered by the existing real-camera test.
- **Blast radius:** refactors are additive (controller camera builder, harness
  extraction) and guarded by the existing on-device + Linux suites.
- **Deferred:** real provider delivery (live-channel plan), deterministic
  fake-native clip tests, on-device tests for future workstreams (privacy zones,
  tamper, watchdog, schedule — they extend this harness when they land).