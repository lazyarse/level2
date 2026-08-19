# Person Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add on-device person detection as a motion-gated alert trigger (YOLO26n via a hand-rolled LiteRT `flutter_litert` wrapper), reusing the color analysis stream and motion-gating pipeline built in the face detection phase.

**Architecture:** `PersonDetector` (a `FrameDetector`) delegates to a `PersonEngine` abstraction. The real `YoloPersonEngine` wraps `flutter_litert`'s `Interpreter`, letterboxes the color `AnalysisFrame` to 640×640, runs `yolo26n_w8a32.tflite` (input `[1,3,640,640]` float32 NCHW; output `[1,84,8400]` float32 — 4 box coords + 80 class scores per 8400 anchor, person = class 0), decodes + NMS's the boxes in Dart, and triggers on ≥1 person. Mock engine for pure unit tests.

**Tech Stack:** Flutter/Dart, `flutter_litert ^3.8.0` (already a transitive dep via `face_detection_tflite`; becomes a direct dep), `yolo26n_w8a32.tflite` (~2.9 MB, AGPL-3.0) bundled as an asset, `package:image` (already a dep).

**Spec:** `docs/plans/2026-08-19-person-detection-design.md`

**Deviation from main design doc (`2026-08-17-security-cam-app-design.md`, Phase 2):** it says "YOLO26n via LiteRT + `tflite_flutter`". We use `flutter_litert` instead — `tflite_flutter` is Android-only in this project (its Linux CMake target `flutter_tflite_plugin` collides with `flutter_litert`, so it is excluded from Linux builds; see `linux/flutter/plugins_managed.cmake`). `flutter_litert` IS the LiteRT runtime and keeps the fast Linux desktop dev loop + real-model gate working (same decision as the face phase). Model stays YOLO26n as locked.

**Model I/O (verified from the .tflite FlatBuffer + official app source):**
- Input `serving_default_args_0`: shape `[1, 3, 640, 640]`, **float32**, NCHW (channel-first), values normalized `[0, 1]`, RGB.
- Output `serving_default_output_0_output`: shape `[1, 84, 8400]`, **float32**. Layout is channel-major: element `(row r, anchor i)` at `r*8400 + i`. Rows 0–3 = `(cx, cy, w, h)` **normalized [0,1]** (graph divides by stride then imgsz); rows 4–83 = per-class scores, **sigmoid already applied by the graph** (person = **row 4**). `nc=80` (COCO), `N=8400`.
- Not end-to-end: no built-in NMS (app does NMS in post-processing). No embedded TFLite metadata (min_runtime_version only); appends a ZIP with `metadata.json` (task=detect, head=Detect, end2end=false, stride=32, imgsz=[640,640]).

**Preprocessing (official app pipeline):** letterbox the frame to 640×640 — gain `= min(640/W, 640/H)`, pad bars **black (0,0,0)** with `pad = round(pad/2 − 0.1)`; convert BGR→RGB; normalize to `[0,1]` (`(p − 0)/255`); write planar CHW float32.

**Post-processing:** for each anchor `i` (0..8399): person score = `out[4*8400 + i]`; skip if `< conf` (from `config.threshold`, default 0.25); box `cx=out[i]`, `cy=out[1*8400+i]`, `w=out[2*8400+i]`, `h=out[3*8400+i]` (all [0,1]); `x = cx − w/2, y = cy − h/2`. Convert to input pixels (`*640`), undo letterbox: `x_orig = (x_model − padX)/gain`, `y_orig = (y_model − padY)/gain`; clamp to frame; run IoU NMS (threshold **0.7**, max 30) sorted by score desc; trigger if any box remains.

---

### Task 1: YOLO person engine + model asset

**Files:**
- Add: `security_cam/assets/yolo26n_w8a32.tflite` (downloaded, ~2.9 MB, AGPL-3.0)
- Modify: `security_cam/pubspec.yaml` (asset entry + `flutter_litert` direct dep)
- Add: `security_cam/lib/detection/person/person_engine.dart`
- Add: `security_cam/lib/detection/person/yolo_person_engine.dart`
- Add: `security_cam/lib/detection/person/mock_person_engine.dart`
- Test: `security_cam/test/yolo_person_engine_test.dart` (Create)

- [ ] **Step 1: Write the failing test for the pure decode/NMS helpers**

Create `security_cam/test/yolo_person_engine_test.dart` covering the pure-Dart helpers (no native inference needed): letterbox geometry, `[1,84,8400]` decode, and NMS. Expose the decode/NMS as testable top-level functions in `yolo_person_engine.dart` (e.g. `List<PersonBox> decodeYolo26(Uint8List output, {double conf, double iou})`).

Assertions:
- A synthetic output tensor with a high person score at one anchor decodes to one box in frame coordinates (normalized decode → `*640` → letterbox undo applied).
- Two overlapping boxes: NMS (IoU 0.7) keeps the higher-scoring one; two far-apart boxes are both kept.
- Person score below `conf` → no box.

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && flutter test test/yolo_person_engine_test.dart`
Expected: FAIL — `yolo_person_engine.dart` is not defined.

- [ ] **Step 3: Implement the engine + helpers**

`person_engine.dart`:
```dart
/// A detected person bounding box (top-left x/y, bottom-right x/y) + score.
typedef PersonBox = (double, double, double, double, double); // x1,y1,x2,y2,score

abstract class PersonEngine {
  Future<void> init();
  Future<List<PersonBox>> detectPersons(ColorBitmap frame);
  Future<void> dispose();
}
```

`yolo_person_engine.dart`:
- `YoloPersonEngine({double confThreshold = 0.25, double iouThreshold = 0.7, int maxDetections = 30})`.
- `init()`: `interpreter = await Interpreter.fromAsset('assets/yolo26n_w8a32.tflite');`
- `detectPersons(frame)`: letterbox `frame.bgr` → 640×640 BGR (pad **black 0,0,0**), convert to RGB float32 `[0,1]`, write planar CHW into a `Float32List(1*3*640*640)`; `inputTensor.setTo(input)`; output `Float32List(84*8400)`; `outputTensor.copyTo(out)`; `decodeYolo26(out, ...)` (normalized boxes → letterbox undo → frame coords); `nms(...)`.
- Top-level helpers (pure Dart, unit-testable): `letterboxGain`, `decodeYolo26`, `nms`.
- `dispose()`: `interpreter.close()`.

`mock_person_engine.dart`: `MockPersonEngine` with a configurable `detections` list / `defaultDetect` override (mirrors `MockFaceDetector`).

- [ ] **Step 4: Add the model asset + deps**

- Download `https://github.com/ultralytics/yolo-flutter-app/releases/download/v0.6.6/yolo26n_w8a32.tflite` into `security_cam/assets/` (already verified: 2,875,553 bytes).
- `pubspec.yaml`: add `assets/yolo26n_w8a32.tflite` to `flutter.assets`; add `flutter_litert: ^3.8.0` to `dependencies`.
- Run `date -R && flutter pub get` (regenerates the Linux plugin list — the managed CMake filter keeps working).

- [ ] **Step 5: Verify**

Run: `date -R && flutter test test/yolo_person_engine_test.dart`
Expected: PASS (helpers + mock, no native inference).

---

### Task 2: `PersonDetector` + factory

**Files:**
- Add: `security_cam/lib/detection/person/person_detector.dart`
- Add: `security_cam/lib/detection/person/person_engine_factory.dart`
- Test: `security_cam/test/person_detector_test.dart` (Create)

- [ ] **Step 1: Write the failing test**

`test/person_detector_test.dart`:
- With `MockPersonEngine` returning one box → `detected` true, result contains `TriggerType.person`, box coordinates preserved.
- With `MockPersonEngine` returning no boxes → `detected` false.
- `analyzeFrameAsync` awaited (async path) returns the same result; `reset()` clears state.
- Factory `buildPersonEngine()` returns `MockPersonEngine` in tests (injectable) and real engine otherwise (mirror `buildFaceEngine`).

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && flutter test test/person_detector_test.dart`
Expected: FAIL — `PersonDetector` is not defined.

- [ ] **Step 3: Implement**

`person_detector.dart`: `class PersonDetector extends FrameDetector` with `id = 'person'`, `triggerType = TriggerType.person`, holding a `PersonEngine`. `analyzeFrame`/`analyzeFrameAsync` run the engine, gate the score on `config.threshold` via the engine's `confThreshold`, and produce a `DetectionResult` (reuse the same shape as `FaceDetector`).

`person_engine_factory.dart`: `PersonEngine buildPersonEngine()` — real on mobile/Linux, mock for tests (same pattern as `buildFaceEngine` in `lib/detection/face/face_engine_factory.dart`).

- [ ] **Step 4: Verify**

Run: `date -R && flutter test test/person_detector_test.dart`
Expected: PASS.

---

### Task 3: Register person detector + default config + label

**Files:**
- Modify: `security_cam/lib/core/registries.dart`
- Modify: `security_cam/lib/core/settings.dart`
- Modify: `security_cam/lib/event/event_pipeline.dart`
- Test: `security_cam/test/detector_registry_test.dart` (Create)

- [ ] **Step 1: Write the failing test**

`test/detector_registry_test.dart`:
- `detectorRegistry[TriggerType.person]` builds a `PersonDetector` for a `DetectorConfig(type: 'person', ...)`.
- `AppSettings.defaults()` contains a `person` config with `enabled: false`, `motionGated: true`.
- `AppSettings.fromJson` round-trips the `person` config (JSON → settings → JSON identical).
- `triggerLabel(TriggerType.person) == 'Person'`.

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && flutter test test/detector_registry_test.dart`
Expected: FAIL — person not registered / no label.

- [ ] **Step 3: Implement**

- `registries.dart`: add `TriggerType.person: (c) => PersonDetector(c)`.
- `settings.dart` `AppSettings.defaults()`: add a `person` entry (disabled, `motionGated: true`, `threshold: 0.5`, `persistenceFrames: 2`, `routeToChannelIds: ['telegram']`).
- `event_pipeline.dart` `_label`: add `TriggerType.person => 'Person'` (before the default case).

- [ ] **Step 4: Verify + full suite**

Run: `date -R && flutter test`
Expected: PASS (previous 157 + new tests).

---

### Task 4: Linux desktop integration test (real YOLO model)

**Files:**
- Add: `security_cam/integration_test/person_detection_linux_test.dart`
- Modify: `security_cam/pubspec.yaml` (asset dir `integration_test/assets/` already declared)

- [ ] **Step 1: Write the test**

Mirror `face_detection_linux_test.dart` (reuse the `loadBgr` helper pattern via `package:image` + `rootBundle`):
- Person-positive: `messi5.jpg`, `astronaut.png`, `camera.png` (all already bundled; all contain people) → `detectPersons` returns ≥1 box, box within frame bounds.
- Person-negative: a blank gray frame → `detectPersons` returns empty.

- [ ] **Step 2: Run the real-model gate**

Run: `date -R && flutter test integration_test/person_detection_linux_test.dart -d linux`
Expected: PASS — the real YOLO26n model loads and detects people on Linux. (If a specific image is ambiguous for the model, drop it from the positive set and keep the others — the gate needs one solid person-positive.)

---

### Task 5: Android emulator integration scenario

**Files:**
- Modify: `security_cam/integration_test/monitoring_on_device_test.dart`

- [ ] **Step 1: Add a person-enabled scenario**

Mirror the face scenario: extend `DeviceHarness.create` with `bool enablePerson = false` (enables the `person` config via `config.copyWith(enabled: true, motionGated: true)` before `controller.init()`), and add a test that starts monitoring with person enabled, waits in the `monitoring` state for ~30 s, asserts no crash (`controller.error` null), then stops.

- [ ] **Step 2: Run the Android integration suite**

Preconditions per `AGENTS.md`: only one AOSP emulator at a time; ≥4 GiB free RAM; loadavg < 75% of cores.

Run: `date -R && ANDROID_HOME=/home/tpa/code/android-env/android-sdk security_cam/tool/run_android_integration_tests.sh pixel_24_aosp`
Expected: suite passes; the person-gated path runs without error (640×640 YOLO is slow on the swiftshader x86_64 CPU but motion-gated, so the no-crash assertion holds).

- [ ] **Step 3: Clean up the emulator**

Run: `date -R && adb -s <serial> emu kill; pkill -9 -f qemu-system; ps aux | rg 'qemu-system' || echo clean`
Expected: no `qemu-system` processes remain.

- [ ] **Step 4: Commit**

```bash
git add security_cam/lib/detection/person security_cam/test/person_detector_test.dart \
        security_cam/test/yolo_person_engine_test.dart security_cam/test/detector_registry_test.dart \
        security_cam/lib/core/registries.dart security_cam/lib/core/settings.dart \
        security_cam/lib/event/event_pipeline.dart security_cam/integration_test \
        security_cam/assets/yolo26n_w8a32.tflite security_cam/pubspec.yaml
git commit -m "feat: person detection trigger (YOLO26n via flutter_litert)"
```

---

## Self-Review notes

- **Reuse:** color stream, `analysisResolution`, motion gating, `_DetectorCard` settings UI, registry, `TriggerType.person` — all built in the face phase; person phase adds only the engine + detector + registration + tests.
- **Spec coverage:** YOLO26n model (locked) ✓; LiteRT runtime via flutter_litert ✓; motion gating ✓; real-model Linux gate ✓; on-device scenario ✓.
- **License:** model is AGPL-3.0 — compatible with the app's AGPL-3.0 (same as main design doc).
- **Known risks to verify in Task 1/4:** exact class-score sigmoid behavior and color order in the exported head (both are standard Ultralytics pipeline; ground-truth I/O shape already verified from the .tflite file), and 640×640 inference latency on the swiftshader emulator (mitigated by motion gating).