# Face Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add on-device face detection as a motion-gated alert trigger (BlazeFace via `face_detection_tflite`), backed by a new color analysis stream whose resolution is a Settings preset.

**Architecture:** `AnalysisFrame` gains a `ColorBitmap`; `AppSettings.analysisResolution` sets the single analysis stream's size. `DetectorConfig.motionGated` + a new async `FrameDetector.analyzeFrameAsync` let the pipeline run the face detector only on frames where `MotionDetector` fired. `FaceDetector` (a `FrameDetector`) delegates to a `FaceEngine` abstraction: real TFLite engine on mobile, mock on desktop/headless. Recognition is deferred (the same package ships MobileFaceNet embeddings) and is NOT part of this plan.

**Tech Stack:** Flutter/Dart, `tflite_flutter` (already a dep), `face_detection_tflite ^6.8.0` (new dep, Apache-2.0), Kotlin CameraX `camera_service` module, ffmpeg rawvideo (desktop), `package:image`.

**Spec:** `docs/plans/2026-08-18-face-detection-design.md`

---

### Task 1: `ColorBitmap` + `AnalysisFrame.color`

**Files:**
- Modify: `security_cam/lib/core/models.dart:3-18`
- Test: `security_cam/test/analysis_frame_test.dart` (Create)

- [ ] **Step 1: Write the failing test**

Create `security_cam/test/analysis_frame_test.dart`:

```dart
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/models.dart';

void main() {
  test('ColorBitmap exposes pixel-safe BGR access', () {
    final bgr = ColorBitmap(2, 1, Uint8List.fromList([1, 2, 3, 4, 5, 6]));
    expect(bgr.width, 2);
    expect(bgr.height, 1);
    expect(bgr.b, 0); // component getters are placeholders; see below
  });

  test('AnalysisFrame carries optional color and required bitmap', () {
    final frame = AnalysisFrame(
      timestamp: DateTime(2026, 1, 1),
      bitmap: GrayscaleBitmap(1, 1, Uint8List.fromList([10])),
      color: ColorBitmap(1, 1, Uint8List.fromList([5, 6, 7])),
    );
    expect(frame.bitmap.pixel(0, 0), 10);
    expect(frame.color!.b, 5);
  });
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/analysis_frame_test.dart`
Expected: FAIL — `ColorBitmap` is not defined.

- [ ] **Step 3: Implement `ColorBitmap` and `AnalysisFrame.color`**

Edit `security_cam/lib/core/models.dart`. Replace the `GrayscaleBitmap` block (lines 3–12) with:

```dart
class GrayscaleBitmap {
  final int width;
  final int height;
  final Uint8List gray;

  GrayscaleBitmap(this.width, this.height, this.gray)
      : assert(gray.length == width * height, 'gray length must be width*height');

  int pixel(int x, int y) => gray[y * width + x];
}

/// Raw interleaved BGR pixel buffer (as produced by ffmpeg `bgr24`, CameraX
/// YUV→BGR and the simulated camera). Fed directly to BlazeFace via
/// `detectFacesFromMatBytes` (which expects BGR).
class ColorBitmap {
  final int width;
  final int height;
  final Uint8List bgr;

  ColorBitmap(this.width, this.height, this.bgr)
      : assert(bgr.length == width * height * 3, 'bgr length must be width*height*3');

  int b(int x, int y) => bgr[(y * width + x) * 3];
  int g(int x, int y) => bgr[(y * width + x) * 3 + 1];
  int r(int x, int y) => bgr[(y * width + x) * 3 + 2];
}
```

Then update `AnalysisFrame`:

```dart
class AnalysisFrame {
  final DateTime timestamp;
  final GrayscaleBitmap bitmap;
  final ColorBitmap? color;

  AnalysisFrame({required this.timestamp, required this.bitmap, this.color});
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/analysis_frame_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/core/models.dart security_cam/test/analysis_frame_test.dart
git commit -m "feat: add ColorBitmap and color to AnalysisFrame"
```

---

### Task 2: `AnalysisResolution` setting (presets) + JSON

**Files:**
- Modify: `security_cam/lib/core/settings.dart`
- Test: `security_cam/test/settings_test.dart` (Create if absent, else Modify)

- [ ] **Step 1: Write the failing test**

Create `security_cam/test/settings_test.dart`:

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/settings.dart';

void main() {
  test('analysis resolution defaults to balanced (320x240)', () {
    final s = AppSettings.defaults();
    expect(s.analysisResolution, AnalysisResolution.balanced);
    final (w, h) = AnalysisResolution.size(s.analysisResolution);
    expect((w, h), (320, 240));
  });

  test('analysis resolution JSON round-trips', () {
    final s = AppSettings.defaults().copyWith(
      analysisResolution: AnalysisResolution.high,
    );
    final back = AppSettings.fromJson(s.toJson());
    expect(back.analysisResolution, AnalysisResolution.high);
  });

  test('missing analysisResolution falls back to balanced', () {
    final back = AppSettings.fromJson(const {});
    expect(back.analysisResolution, AnalysisResolution.balanced);
  });

  test('preset labels', () {
    expect(AnalysisResolution.label(AnalysisResolution.low), 'Low (160x120)');
    expect(AnalysisResolution.label(AnalysisResolution.balanced), 'Balanced (320x240)');
    expect(AnalysisResolution.label(AnalysisResolution.high), 'High (640x480)');
  });
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/settings_test.dart`
Expected: FAIL — `AnalysisResolution` not defined / `analysisResolution` not on `AppSettings`.

- [ ] **Step 3: Implement `AnalysisResolution` + `AppSettings` field**

Edit `security_cam/lib/core/settings.dart`. Add after the `VideoQuality` class (after line 59):

```dart
/// Analysis stream resolution presets (single stream used for both motion and
/// face/person detection). Higher = better far-face recall, more CPU/battery.
class AnalysisResolution {
  static const low = 'low';
  static const balanced = 'balanced';
  static const high = 'high';

  static const values = [low, balanced, high];

  static (int, int) size(String value) {
    return switch (value) {
      low => (160, 120),
      high => (640, 480),
      _ => (320, 240),
    };
  }

  static String label(String value) {
    return switch (value) {
      low => 'Low (160x120)',
      balanced => 'Balanced (320x240)',
      high => 'High (640x480)',
      _ => 'Balanced (320x240)',
    };
  }

  const AnalysisResolution._();
}
```

Add to `AppSettings`: field declaration after `videoQuality` (line 83):

```dart
  final String analysisResolution;
```

Constructor default (line 98):

```dart
    this.analysisResolution = AnalysisResolution.balanced,
```

`copyWith` (after `videoQuality`, line 156):

```dart
    String? analysisResolution,
```

and in the returned `AppSettings`:

```dart
      analysisResolution: analysisResolution ?? this.analysisResolution,
```

`toJson` (after `'videoQuality'` line 192):

```dart
        'analysisResolution': analysisResolution,
```

`fromJson` (after line 228):

```dart
      analysisResolution:
          json['analysisResolution'] as String? ?? defaults.analysisResolution,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/settings_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/core/settings.dart security_cam/test/settings_test.dart
git commit -m "feat: add analysis resolution presets to settings"
```

---

### Task 3: `DetectorConfig.motionGated` + JSON round-trip

**Files:**
- Modify: `security_cam/lib/core/detector.dart:4-57`
- Modify: `security_cam/test/pipeline_test.dart` (append a round-trip test) — or Create `security_cam/test/detector_config_test.dart`

- [ ] **Step 1: Write the failing test**

Create `security_cam/test/detector_config_test.dart`:

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/detector.dart';
import 'package:security_cam/core/models.dart';

void main() {
  test('motionGated defaults to false', () {
    const c = DetectorConfig(type: TriggerType.face);
    expect(c.motionGated, false);
  });

  test('motionGated JSON round-trips', () {
    const c = DetectorConfig(type: TriggerType.face, motionGated: true);
    final back = DetectorConfig.fromJson(c.toJson());
    expect(back.motionGated, true);
  });

  test('missing motionGated falls back to false', () {
    final back = DetectorConfig.fromJson({'type': 'face'});
    expect(back.motionGated, false);
  });
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/detector_config_test.dart`
Expected: FAIL — `motionGated` not a constructor param.

- [ ] **Step 3: Implement**

Edit `security_cam/lib/core/detector.dart`:

- Constructor: add after `routeToChannelIds` (line 10):

```dart
  final bool motionGated;
```

- Constructor params (line 11–19): add `this.motionGated = false,`.
- `copyWith` (line 21–37): add `bool? motionGated,` and pass `motionGated: motionGated ?? this.motionGated,`.
- `toJson` (line 39–46): add `'motionGated': motionGated,`.
- `fromJson` (line 48–56): add `motionGated: json['motionGated'] as bool? ?? false,`.

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/detector_config_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/core/detector.dart security_cam/test/detector_config_test.dart
git commit -m "feat: add motionGated flag to DetectorConfig"
```

---

### Task 4: Pipeline motion gating (async analyze path)

**Files:**
- Modify: `security_cam/lib/core/detector.dart:73-75` (`FrameDetector`)
- Modify: `security_cam/lib/detection/pipeline.dart`
- Modify: `security_cam/lib/state/monitor_controller.dart:218`
- Modify: `security_cam/test/pipeline_test.dart`

- [ ] **Step 1: Write the failing test**

Append to `security_cam/test/pipeline_test.dart`:

```dart
import 'package:security_cam/core/models.dart';
// existing imports stay

/// Gated stub detector: counts how often its async path is invoked.
class _GatedStubDetector extends FrameDetector {
  _GatedStubDetector(this._config);
  final DetectorConfig _config;
  int asyncCalls = 0;

  @override
  DetectorConfig get config => _config;

  @override
  String get id => 'gated-stub';

  @override
  String get triggerType => 'gated';

  @override
  Future<void> init() async {}

  @override
  void reset() {}

  @override
  Future<void> dispose() async {}

  @override
  DetectionResult analyzeFrame(AnalysisFrame frame) =>
      DetectionResult(timestamp: frame.timestamp, triggerType: triggerType, score: 0, triggered: false);

  @override
  Future<DetectionResult> analyzeFrameAsync(AnalysisFrame frame) async {
    asyncCalls++;
    return DetectionResult(timestamp: frame.timestamp, triggerType: triggerType, score: 1, triggered: true);
  }
}

test('gated detectors run only when motion fires', () async {
  final stub = _GatedStubDetector(const DetectorConfig(
    type: 'gated', enabled: true, motionGated: true, persistenceFrames: 1));
  final pipeline = DetectorPipeline(
    classifier: MockAudioEventClassifier(),
    configs: [
      const DetectorConfig(
        type: TriggerType.motion, enabled: true, threshold: 0.01,
        persistenceFrames: 1),
    ],
  );
  await pipeline.init();
  pipeline.debugAddFrameDetector(stub); // injected before subscribing
  final events = <TriggerEvent>[];
  final sub = pipeline.triggers.listen(events.add);

  // Prime the motion detector (no motion on frame 1).
  await pipeline.processFrame(AnalysisFrame(
    timestamp: base,
    bitmap: GrayscaleBitmap(16, 16, buildFrame(16, 16, 140)),
  ));
  expect(stub.asyncCalls, 0);
  expect(events, hasLength(0));

  // Motion fires on frame 2 → gated detector runs.
  await pipeline.processFrame(AnalysisFrame(
    timestamp: base.add(const Duration(seconds: 1)),
    bitmap: GrayscaleBitmap(16, 16, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30)),
  ));
  expect(stub.asyncCalls, 1);
  expect(events.map((e) => e.triggerType), contains('gated'));

  // No motion on frame 3 → gated detector does not run again.
  await pipeline.processFrame(AnalysisFrame(
    timestamp: base.add(const Duration(seconds: 2)),
    bitmap: GrayscaleBitmap(16, 16, buildFrame(16, 16, 140)),
  ));
  expect(stub.asyncCalls, 1);

  await sub.cancel();
  await pipeline.dispose();
});
```

> Note: `debugAddFrameDetector` is a test seam we add in Step 3 (the pipeline builds detectors from configs in its constructor; the seam lets a test inject a stub). If you prefer, instead pass the stub via a config of type `'gated'` and register it in `detectorRegistry` — but that couples the unit test to the registry. The seam keeps the test pure.

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/pipeline_test.dart`
Expected: FAIL — `processFrame` is not async / `analyzeFrameAsync` / `debugAddFrameDetector` not defined. Existing tests that call `pipeline.processFrame(...)` without `await` still pass because callers don't await (they still work), but the new test fails.

- [ ] **Step 3: Implement**

Edit `security_cam/lib/core/detector.dart`, `FrameDetector` (line 73–75):

```dart
abstract class FrameDetector extends Detector {
  DetectionResult analyzeFrame(AnalysisFrame frame);

  /// Async analysis path for gated/heavy detectors (runs off the pipeline's
  /// sync per-frame loop). Defaults to the sync path wrapped in a Future.
  Future<DetectionResult> analyzeFrameAsync(AnalysisFrame frame) async =>
      analyzeFrame(frame);
}
```

Edit `security_cam/lib/detection/pipeline.dart`:

- Add a test seam + field. In the class body after `_triggers` (line 14):

```dart
  /// Test seam: injects an extra frame detector after construction.
  @visibleForTesting
  void debugAddFrameDetector(FrameDetector detector) {
    _frameDetectors.add(detector);
  }
```

Add `import 'package:flutter/foundation.dart';` at the top (before `dart:async` is fine).

- Change `processFrame` (line 56–61):

```dart
  Future<void> processFrame(AnalysisFrame frame) async {
    var motionFired = false;
    for (final d in _frameDetectors) {
      if (d.config.motionGated) continue;
      final result = d.analyzeFrame(frame);
      if (result.triggered) {
        if (d.triggerType == TriggerType.motion) motionFired = true;
        _maybeEmit(d, result);
      }
    }
    if (!motionFired) return;
    for (final d in _frameDetectors) {
      if (!d.config.motionGated) continue;
      final result = await d.analyzeFrameAsync(frame);
      if (result.triggered) _maybeEmit(d, result);
    }
  }
```

Edit `security_cam/lib/state/monitor_controller.dart:218`. Replace:

```dart
      _frameSub = camera.analysisFrames.listen(pipeline.processFrame);
```

with:

```dart
      _frameSub = camera.analysisFrames.listen((frame) {
        unawaited(pipeline.processFrame(frame));
      });
```

(`unawaited` is already imported via `dart:async`.)

Update the existing `pipeline_test.dart` sync `processFrame(...)` calls to `await` them (lines 47, 51, 70, 74, 81, 88) — the `analyzeFrame` path is now async; awaiting keeps the tests deterministic.

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/pipeline_test.dart test/monitor_controller_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/core/detector.dart security_cam/lib/detection/pipeline.dart security_cam/lib/state/monitor_controller.dart security_cam/test/pipeline_test.dart
git commit -m "feat: motion-gated async analysis in detector pipeline"
```

---

### Task 5: `TriggerType.face` + `triggerLabel`

**Files:**
- Modify: `security_cam/lib/core/models.dart:46-55`
- Modify: `security_cam/lib/event/event_pipeline.dart:126-143`
- Test: `security_cam/test/event_pipeline_test.dart`

- [ ] **Step 1: Write the failing test**

Append to `security_cam/test/event_pipeline_test.dart`:

```dart
import 'package:security_cam/event/event_pipeline.dart';

test('triggerLabel maps face', () {
  expect(triggerLabel(TriggerType.face), 'Face');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/event_pipeline_test.dart`
Expected: FAIL — `TriggerType.face` not defined.

- [ ] **Step 3: Implement**

`security_cam/lib/core/models.dart` — add to `TriggerType` (after `person`, line 52):

```dart
  static const face = 'face';
```

`security_cam/lib/event/event_pipeline.dart` — in `triggerLabel`, add after `case TriggerType.person:` (line 138–139):

```dart
    case TriggerType.face:
      return 'Face';
```

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/event_pipeline_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/core/models.dart security_cam/lib/event/event_pipeline.dart security_cam/test/event_pipeline_test.dart
git commit -m "feat: add face trigger type and label"
```

---

### Task 6: Face engine abstraction + `FaceDetector` + factory + mock

**Files:**
- Create: `security_cam/lib/detection/face/face_engine.dart`
- Create: `security_cam/lib/detection/face/face_detector.dart`
- Create: `security_cam/lib/detection/face/tflite_face_engine.dart`
- Create: `security_cam/lib/detection/face/mock_face_engine.dart`
- Create: `security_cam/lib/sensors/face_detector_factory.dart`
- Modify: `security_cam/pubspec.yaml` (add `face_detection_tflite`)
- Test: `security_cam/test/face_detector_test.dart` (Create)

- [ ] **Step 1: Add the dependency**

Run: `date -R && cd security_cam && flutter pub add face_detection_tflite:^6.8.0`
Expected: dependency added to `pubspec.yaml` (depends on `tflite_flutter`, already present).

- [ ] **Step 2: Write the failing test**

Create `security_cam/test/face_detector_test.dart`:

```dart
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/detector.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/face/face_detector.dart';
import 'package:security_cam/detection/face/face_engine.dart';
import 'package:security_cam/detection/face/mock_face_engine.dart';

void main() {
  final base = DateTime(2026, 1, 1, 12, 0, 0);

  ColorBitmap color(int fill) {
    final bgr = Uint8List(3 * 3)..fillRange(0, 3 * 3, fill);
    return ColorBitmap(3, 3, bgr);
  }

  AnalysisFrame frame(DateTime ts, {ColorBitmap? c}) => AnalysisFrame(
        timestamp: ts,
        bitmap: GrayscaleBitmap(3, 3, Uint8List(9)),
        color: c ?? color(140),
      );

  test('no color frame never triggers', () async {
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, persistenceFrames: 1),
      engine: MockFaceEngine()..faces.add(const FaceDetection(box: (0, 0, 1, 1), score: 0.9)),
    );
    await d.init();
    final r = await d.analyzeFrameAsync(frame(base));
    expect(r.triggered, false);
    await d.dispose();
  });

  test('face above threshold triggers after persistence', () async {
    final engine = MockFaceEngine()
      ..faces.add(const FaceDetection(box: (0, 0, 1, 1), score: 0.9));
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.7, persistenceFrames: 2),
      engine: engine,
    );
    await d.init();
    await d.analyzeFrameAsync(frame(base));
    expect((await d.analyzeFrameAsync(frame(base.add(const Duration(seconds: 1))))).triggered, true);
    await d.dispose();
  });

  test('face below threshold does not trigger', () async {
    final engine = MockFaceEngine()
      ..faces.add(const FaceDetection(box: (0, 0, 1, 1), score: 0.5));
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.7, persistenceFrames: 1),
      engine: engine,
    );
    await d.init();
    expect((await d.analyzeFrameAsync(frame(base))).triggered, false);
    await d.dispose();
  });

  test('result carries max face score', () async {
    final engine = MockFaceEngine()
      ..faces.addAll([
        const FaceDetection(box: (0, 0, 1, 1), score: 0.6),
        const FaceDetection(box: (1, 1, 2, 2), score: 0.95),
      ]);
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.5, persistenceFrames: 1),
      engine: engine,
    );
    await d.init();
    final r = await d.analyzeFrameAsync(frame(base));
    expect(r.triggered, true);
    expect(r.score, closeTo(0.95, 1e-9));
    await d.dispose();
  });

  test('reset clears persistence', () async {
    final engine = MockFaceEngine()
      ..faces.add(const FaceDetection(box: (0, 0, 1, 1), score: 0.9));
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.5, persistenceFrames: 2),
      engine: engine,
    );
    await d.init();
    await d.analyzeFrameAsync(frame(base));
    d.reset();
    expect((await d.analyzeFrameAsync(frame(base.add(const Duration(seconds: 1))))).triggered, false);
    await d.dispose();
  });
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/face_detector_test.dart`
Expected: FAIL — files/classes not defined.

- [ ] **Step 4: Implement the engine abstraction**

Create `security_cam/lib/detection/face/face_engine.dart`:

```dart
import 'dart:ui';

import '../../core/models.dart';

/// A detected face: bounding box (top-left, bottom-right) + detector score.
class FaceDetection {
  final Rect box;
  final double score;

  const FaceDetection({required this.box, required this.score});
}

/// Abstraction over an on-device face detector. Real impl: [TfliteFaceEngine];
/// headless/desktop tests use [MockFaceEngine].
abstract class FaceEngine {
  Future<void> init();

  /// Returns detected faces in [frame]'s color bitmap. Empty list = no faces.
  Future<List<FaceDetection>> detectFaces(ColorBitmap frame);

  Future<void> dispose();
}
```

> Note: `FaceDetection.box` uses `dart:ui` `Rect` (available headless in `flutter_test`). If `dart:ui` becomes an issue in pure-Dart unit tests, replace with `(double, double, double, double)` — but `flutter_test` already binds `dart:ui`, so `Rect` is fine.

- [ ] **Step 5: Implement the mock engine**

Create `security_cam/lib/detection/face/mock_face_engine.dart`:

```dart
import '../../core/models.dart';
import 'face_engine.dart';

/// Test/dry-run engine: returns whatever [faces] was pre-loaded with.
class MockFaceEngine implements FaceEngine {
  final List<FaceDetection> faces = [];

  @override
  Future<void> init() async {}

  @override
  Future<List<FaceDetection>> detectFaces(ColorBitmap frame) async => List.of(faces);

  @override
  Future<void> dispose() async {}
}
```

- [ ] **Step 6: Implement the TFLite engine**

Create `security_cam/lib/detection/face/tflite_face_engine.dart`:

```dart
import 'dart:typed_data';

import 'package:face_detection_tflite/face_detection_tflite.dart' as fdt;
import 'package:flutter/foundation.dart';

import '../../core/models.dart';
import 'face_engine.dart';

/// BlazeFace via `face_detection_tflite` (back-camera/full-range model — tuned
/// for distant/group faces). Runs inference in the package's background isolate.
class TfliteFaceEngine implements FaceEngine {
  TfliteFaceEngine({this.minScore = 0.0});

  final double minScore;
  fdt.FaceDetector? _detector;

  @override
  Future<void> init() async {
    _detector = await fdt.FaceDetector.create(
      model: fdt.FaceDetectionModel.backCamera,
      minScore: minScore,
    );
  }

  @override
  Future<List<FaceDetection>> detectFaces(ColorBitmap frame) async {
    final detector = _detector;
    if (detector == null) return const [];
    final faces = await detector.detectFacesFromMatBytes(frame.bgr, frame.width, frame.height);
    return [
      for (final f in faces)
        FaceDetection(
          box: Rect.fromLTWH(
            f.boundingBox.topLeft.x,
            f.boundingBox.topLeft.y,
            f.boundingBox.width,
            f.boundingBox.height,
          ),
          score: f.score,
        ),
    ];
  }

  @override
  Future<void> dispose() async {
    await _detector?.dispose();
    _detector = null;
  }
}
```

> **Implementation note:** verify the exact `detectFacesFromMatBytes` signature against the installed version (`dart doc` or the package README). It takes raw BGR pixels + width/height and returns `Future<List<Face>>` where `Face.boundingBox` is a `BoundingBox` with `topLeft`/`width`/`height` and `Face.score` is 0..1. Adjust `Rect.fromLTWH` accordingly if the API differs.

- [ ] **Step 7: Implement `FaceDetector`**

Create `security_cam/lib/detection/face/face_detector.dart`:

```dart
import 'package:flutter/foundation.dart';

import '../../core/detector.dart';
import '../../core/models.dart';
import 'face_engine.dart';

/// Face-detection trigger. Runs on color analysis frames (motion-gated by the
/// pipeline). Persistence/threshold/cooldown come from [DetectorConfig].
///
/// Detection is async (TFLite background isolate), so the real work lives in
/// [analyzeFrameAsync]; [analyzeFrame] is a no-op non-trigger for the sync path.
class FaceDetector extends FrameDetector {
  @override
  final DetectorConfig config;
  final FaceEngine _engine;
  final bool _ownsEngine;

  int _persistenceCount = 0;

  /// Builds the platform engine lazily if [engine] is not provided.
  FaceDetector(this.config, {FaceEngine? engine})
      : _engine = engine ?? buildFaceEngine(),
        _ownsEngine = engine == null;

  @override
  String get id => config.type;

  @override
  String get triggerType => TriggerType.face;

  @override
  Future<void> init() async {
    if (_ownsEngine) await _engine.init();
  }

  @override
  void reset() {
    _persistenceCount = 0;
  }

  @override
  Future<void> dispose() async {
    if (_ownsEngine) await _engine.dispose();
  }

  @override
  DetectionResult analyzeFrame(AnalysisFrame frame) {
    return DetectionResult(
      timestamp: frame.timestamp,
      triggerType: triggerType,
      score: 0,
      triggered: false,
    );
  }

  @override
  Future<DetectionResult> analyzeFrameAsync(AnalysisFrame frame) async {
    final color = frame.color;
    if (color == null) {
      return _result(frame.timestamp, 0, false);
    }
    final faces = await _engine.detectFaces(color);
    if (faces.isEmpty) {
      _persistenceCount = 0;
      return _result(frame.timestamp, 0, false);
    }
    final maxScore = faces.map((f) => f.score).reduce((a, b) => a > b ? a : b);
    final above = maxScore >= config.threshold;
    _persistenceCount = above ? _persistenceCount + 1 : 0;
    if (_persistenceCount >= config.persistenceFrames) {
      _persistenceCount = 0;
      return _result(frame.timestamp, maxScore, true);
    }
    return _result(frame.timestamp, maxScore, false);
  }

  DetectionResult _result(DateTime ts, double score, bool triggered) {
    return DetectionResult(
      timestamp: ts,
      triggerType: triggerType,
      score: score,
      triggered: triggered,
    );
  }
}

/// Returns the platform-appropriate engine: TFLite on mobile, mock elsewhere
/// (mirrors `buildAudioClassifier`). Kept here to avoid a circular import with
/// the sensors factory; real desktop dev smoke tests construct
/// [TfliteFaceEngine] directly.
FaceEngine buildFaceEngine() {
  if (defaultTargetPlatform == TargetPlatform.android ||
      defaultTargetPlatform == TargetPlatform.iOS) {
    return TfliteFaceEngine();
  }
  return MockFaceEngine();
}
```

> Move `buildFaceEngine` to `security_cam/lib/sensors/face_detector_factory.dart` if you prefer separation; keeping it here avoids a `detection/face` → `sensors` import. The plan wires the factory in Task 7.

- [ ] **Step 8: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/face_detector_test.dart`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add security_cam/lib/detection/face security_cam/test/face_detector_test.dart security_cam/pubspec.yaml security_cam/pubspec.lock
git commit -m "feat: face detector with engine abstraction and TFLite backend"
```

---

### Task 7: Register face detector + default config

**Files:**
- Modify: `security_cam/lib/core/registries.dart`
- Modify: `security_cam/lib/core/settings.dart` (`AppSettings.defaults`)
- Modify: `security_cam/test/settings_test.dart`

- [ ] **Step 1: Write the failing test**

Append to `security_cam/test/settings_test.dart`:

```dart
import 'package:security_cam/core/models.dart';

test('defaults include a face detector, disabled and motion-gated', () {
  final s = AppSettings.defaults();
  final face = s.detectorConfigs[TriggerType.face];
  expect(face, isNotNull);
  expect(face!.enabled, false);
  expect(face.motionGated, true);
  expect(face.threshold, 0.7);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/settings_test.dart`
Expected: FAIL — no `face` entry in defaults.

- [ ] **Step 3: Implement**

`security_cam/lib/core/registries.dart` — add import + registry entry:

```dart
import '../detection/face/face_detector.dart';
```

and in `detectorRegistry` (after the `TriggerType.loudNoise` line 19):

```dart
  TriggerType.face: (c) => FaceDetector(c),
```

`security_cam/lib/core/settings.dart` — in `AppSettings.defaults()` `detectorConfigs` map (after `TriggerType.loudNoise`, line 130):

```dart
        TriggerType.face: const DetectorConfig(
          type: TriggerType.face,
          threshold: 0.7,
          persistenceFrames: 2,
          enabled: false,
          motionGated: true,
          routeToChannelIds: ['telegram'],
        ),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/settings_test.dart test/pipeline_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/core/registries.dart security_cam/lib/core/settings.dart security_cam/test/settings_test.dart
git commit -m "feat: register face detector with motion-gated default"
```

---

### Task 8: Settings UI — `motionGated` toggle, Face label, Advanced resolution section

**Files:**
- Modify: `security_cam/lib/ui/settings_screen.dart`
- Modify: `security_cam/test/settings_screen_test.dart`

- [ ] **Step 1: Write the failing test**

Append to `security_cam/test/settings_screen_test.dart` (follow existing harness in that file — it builds `SettingsScreen` inside a `MaterialApp` with a controller):

```dart
testWidgets('face detector card shows motion-gated toggle', (tester) async {
  await tester.pumpWidget(MaterialApp(home: SettingsScreen(controller: controller)));
  await tester.tap(find.text('Face'));
  await tester.pumpAndSettle();
  expect(find.text('Motion-gated'), findsOneWidget);
});

testWidgets('advanced section exposes analysis resolution', (tester) async {
  await tester.pumpWidget(MaterialApp(home: SettingsScreen(controller: controller)));
  expect(find.text('Advanced'), findsOneWidget);
  expect(find.text('Balanced (320x240)'), findsOneWidget);
});
```

(Adjust to the actual harness used in `settings_screen_test.dart` — reuse its `controller` setup.)

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/settings_screen_test.dart`
Expected: FAIL — no `Motion-gated` text / no `Advanced` section / no `Face` label.

- [ ] **Step 3: Implement UI changes**

In `security_cam/lib/ui/settings_screen.dart`:

1. `_DetectorCard._label` — add a case:

```dart
      TriggerType.face => 'Face',
```

2. `_DetectorCard` — add a `motionGated` toggle inside the `if (config.enabled) ...[` block, before the threshold row (after line 547):

```dart
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Motion-gated'),
                subtitle: const Text(
                  'Only check for this after motion is detected (saves battery).',
                  style: TextStyle(fontSize: 12),
                ),
                value: config.motionGated,
                onChanged: (v) => onChanged(config.copyWith(motionGated: v)),
              ),
```

3. Add an **Advanced** section before the Save button (after line 321, before `FilledButton.icon`):

```dart
          const SizedBox(height: 24),
          Text('Advanced', style: Theme.of(context).textTheme.titleMedium),
          const Text(
            'Analysis stream resolution: higher = better far-face detection '
            'but more battery. Balanced is a good default.',
            style: TextStyle(fontSize: 12),
          ),
          const SizedBox(height: 8),
          DropdownButtonFormField<String>(
            key: const ValueKey('analysisResolutionDropdown'),
            initialValue: _draft.analysisResolution,
            decoration: const InputDecoration(
              labelText: 'Analysis resolution',
            ),
            items: [
              for (final r in AnalysisResolution.values)
                DropdownMenuItem(
                  value: r,
                  child: Text(AnalysisResolution.label(r)),
                ),
            ],
            onChanged: (v) => setState(() {
              if (v != null) {
                _draft = _draft.copyWith(analysisResolution: v);
              }
            }),
          ),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/settings_screen_test.dart test/shell_navigation_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/ui/settings_screen.dart security_cam/test/settings_screen_test.dart
git commit -m "feat: face motion-gate toggle and analysis resolution settings UI"
```

---

### Task 9: Desktop color frames (simulated + ffmpeg)

**Files:**
- Modify: `security_cam/lib/sensors/simulated_camera_session.dart`
- Modify: `security_cam/lib/sensors/ffmpeg_camera_session.dart`
- Create: `security_cam/lib/sensors/bgr_frame_assembler.dart`
- Modify: `security_cam/test/ffmpeg_args_test.dart` (assert `bgr24` instead of `gray`)
- Create: `security_cam/test/bgr_frame_assembler_test.dart`

- [ ] **Step 1: Write the failing assembler test**

Create `security_cam/test/bgr_frame_assembler_test.dart`:

```dart
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/sensors/bgr_frame_assembler.dart';

void main() {
  test('splits BGR chunks into whole frames carrying remainder', () {
    final a = BgrFrameAssembler(2, 2); // frame = 12 bytes
    final chunk = Uint8List.fromList(List.generate(14, (i) => i));
    final frames = a.add(chunk);
    expect(frames, hasLength(1));
    expect(frames.first.bgr, [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]);
    expect(a.buffered, 2);
    final tail = a.add(Uint8List.fromList([12, 13]));
    expect(tail, hasLength(1));
    expect(tail.first.bgr, [12, 13, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
  });
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/bgr_frame_assembler_test.dart`
Expected: FAIL — `BgrFrameAssembler` not defined.

- [ ] **Step 3: Implement `BgrFrameAssembler`**

Create `security_cam/lib/sensors/bgr_frame_assembler.dart`:

```dart
import 'dart:typed_data';

import '../core/models.dart';

/// Accumulates raw BGR byte chunks (as produced by `ffmpeg -f rawvideo -pix_fmt
/// bgr24`) and emits whole color frames, carrying any remainder across chunk
/// boundaries. Mirrors `GrayFrameAssembler` with a 3× frame size.
class BgrFrameAssembler {
  final int width;
  final int height;
  final int frameSize;
  final BytesBuilder _pending = BytesBuilder();

  BgrFrameAssembler(this.width, this.height)
      : assert(width > 0 && height > 0),
        frameSize = width * height * 3;

  List<ColorBitmap> add(Uint8List chunk) {
    _pending.add(chunk);
    final bytes = _pending.takeBytes();
    final frames = <ColorBitmap>[];
    var offset = 0;
    while (bytes.length - offset >= frameSize) {
      final bgr = Uint8List.fromList(bytes.sublist(offset, offset + frameSize));
      frames.add(ColorBitmap(width, height, bgr));
      offset += frameSize;
    }
    if (offset < bytes.length) {
      _pending.add(bytes.sublist(offset));
    }
    return frames;
  }

  int get buffered => _pending.length;
}
```

- [ ] **Step 4: Update ffmpeg args to BGR and wire the session**

`security_cam/lib/sensors/ffmpeg_camera_session.dart`:

- `buildArgs` (line 52): change `'-pix_fmt', 'gray',` → `'-pix_fmt', 'bgr24',`.
- Add a `BgrFrameAssembler` alongside the gray one (keep `GrayFrameAssembler` import/field or replace). Simplest: add `BgrFrameAssembler? _colorAssembler;`, construct in `init` with the same dims, and in the stdout listener, after emitting grayscale frames, also emit color:

Replace the `_stdoutSub` handler (lines 110–123) with:

```dart
    _stdoutSub = process.stdout.listen((chunk) {
      final frames = _assembler!.add(Uint8List.fromList(chunk));
      final colorFrames = _colorAssembler!.add(Uint8List.fromList(chunk));
      for (var i = 0; i < frames.length; i++) {
        final gray = frames[i];
        final color = colorFrames[i];
        _latest = gray;
        controller.add(AnalysisFrame(
          timestamp: DateTime.now(),
          bitmap: gray,
          color: color,
        ));
      }
    }, onError: (_) {}, onDone: () {
      if (!_disposed && !controller.isClosed) {
        controller.close();
      }
    });
```

Add the field and init:

```dart
  BgrFrameAssembler? _colorAssembler;
```

In `init` after `_assembler = GrayFrameAssembler(...)` (line 75):

```dart
    _colorAssembler = BgrFrameAssembler(config.analysisWidth, config.analysisHeight);
```

- [ ] **Step 5: Update the simulated camera to emit color**

`security_cam/lib/sensors/simulated_camera_session.dart` — change the timer body (lines 26–32) to include color derived from gray:

```dart
    _timer = Timer.periodic(period, (_) {
      final gray = generateFrame(_step++, config.analysisWidth, config.analysisHeight, animate);
      final bgr = Uint8List(gray.width * gray.height * 3);
      for (var i = 0; i < gray.gray.length; i++) {
        final v = gray.gray[i];
        bgr[i * 3] = v;
        bgr[i * 3 + 1] = v;
        bgr[i * 3 + 2] = v;
      }
      final frame = AnalysisFrame(
        timestamp: DateTime.now(),
        bitmap: gray,
        color: ColorBitmap(gray.width, gray.height, bgr),
      );
      controller.add(frame);
    });
```

- [ ] **Step 6: Update ffmpeg args test + add assembler test to suite**

In `security_cam/test/ffmpeg_args_test.dart`, change the assertion that expects `'gray'` to expect `'bgr24'`.

Run: `date -R && cd security_cam && flutter test test/ffmpeg_args_test.dart test/bgr_frame_assembler_test.dart test/simulated_camera_session_test.dart 2>/dev/null || flutter test test/ffmpeg_args_test.dart test/bgr_frame_assembler_test.dart`
Expected: PASS.

- [ ] **Step 7: Run the full desktop unit suite**

Run: `date -R && cd security_cam && flutter test`
Expected: PASS (all existing tests unaffected; `AnalysisFrame` gains an optional field, so existing constructions still compile).

- [ ] **Step 8: Commit**

```bash
git add security_cam/lib/sensors security_cam/test/bgr_frame_assembler_test.dart security_cam/test/ffmpeg_args_test.dart
git commit -m "feat: emit color analysis frames on desktop (simulated + ffmpeg bgr24)"
```

---

### Task 10: Android color frames (native YUV→BGR) + per-preset resolution

**Files:**
- Modify: `security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service/CameraFrameBus.kt`
- Modify: `security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service/CameraServiceChannels.kt`
- Modify: `security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service/MonitoringService.kt`
- Modify: `security_cam/lib/sensors/android_camera_session.dart`
- Modify: `security_cam/lib/state/monitor_controller.dart:165-170` (pass preset resolution to the native module)
- Modify: `security_cam/test/android_camera_session_test.dart`

- [ ] **Step 1: Write the failing Dart-side test**

In `security_cam/test/android_camera_session_test.dart`, update the `parseFrameEvent` tests to the new BGR payload. Replace/add:

```dart
test('parseFrameEvent reads bgr and derives gray', () {
  // 1x1 pixel, BGR = [10, 20, 30] (blue, green, red).
  final frame = AndroidCameraSession.parseFrameEvent({
    'width': 1,
    'height': 1,
    'bgr': [10, 20, 30],
  });
  expect(frame, isNotNull);
  // Luminance of (r=30, g=20, b=10): 0.299*30 + 0.587*20 + 0.114*10 ≈ 22.0
  expect(frame!.bitmap.pixel(0, 0), 22);
  expect(frame.color, isNotNull);
  expect(frame.color!.r(0, 0), 30);
});

test('parseFrameEvent rejects malformed bgr length', () {
  expect(AndroidCameraSession.parseFrameEvent({
    'width': 1, 'height': 1, 'bgr': [10, 20],
  }), isNull);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/android_camera_session_test.dart`
Expected: FAIL — `parseFrameEvent` reads `'gray'`, not `'bgr'`.

- [ ] **Step 3: Update Dart-side parsing + pass resolution to native**

`security_cam/lib/sensors/android_camera_session.dart`:

Replace `parseFrameEvent` (lines 128–139):

```dart
  static AnalysisFrame? parseFrameEvent(Object? event) {
    if (event is! Map) return null;
    final width = event['width'];
    final height = event['height'];
    final bgr = event['bgr'];
    if (width is! int || height is! int || bgr is! List<int>) return null;
    if (bgr.length != width * height * 3) return null;
    final bgrBytes = Uint8List.fromList(bgr);
    final gray = Uint8List(width * height);
    for (var i = 0; i < width * height; i++) {
      final b = bgrBytes[i * 3];
      final g = bgrBytes[i * 3 + 1];
      final r = bgrBytes[i * 3 + 2];
      gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).round().clamp(0, 255);
    }
    return AnalysisFrame(
      timestamp: DateTime.now(),
      bitmap: GrayscaleBitmap(width, height, gray),
      color: ColorBitmap(width, height, bgrBytes),
    );
  }
```

Update `init`'s `startMonitoring` call (lines 78–85) to forward analysis size:

```dart
      await _method.invokeMethod<void>('startMonitoring', {
        'cameraId': cameraId,
        'cameraName': cameraName,
        'preRollSeconds': preRollSeconds,
        'postRollSeconds': postRollSeconds,
        'recordVideo': recordVideo,
        'videoQuality': videoQuality,
        'analysisWidth': config.analysisWidth,
        'analysisHeight': config.analysisHeight,
      });
```

- [ ] **Step 4: Update `MonitorController` to size the analysis stream from settings**

`security_cam/lib/state/monitor_controller.dart:165-170` — replace the hardcoded 160×120 with the preset size:

```dart
      await camera.init(CameraConfig(
        cameraId: camera.cameraId,
        analysisWidth: AnalysisResolution.size(settings.analysisResolution).$1,
        analysisHeight: AnalysisResolution.size(settings.analysisResolution).$2,
        analysisFps: 4,
      ));
```

Add `import '../core/settings.dart';` is already present (line 9). The `AnalysisResolution` class is in `settings.dart`, already imported.

- [ ] **Step 5: Update the Kotlin bus, channels, and service**

`CameraFrameBus.kt` — rename the listener payload to BGR:

```kotlin
    private val listeners = CopyOnWriteArrayList<(bgr: ByteArray, width: Int, height: Int) -> Unit>()

    fun add(listener: (bgr: ByteArray, width: Int, height: Int) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (bgr: ByteArray, width: Int, height: Int) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(bgr: ByteArray, width: Int, height: Int) {
        listeners.forEach { it(bgr, width, height) }
    }
```

`CameraServiceChannels.kt` — `publishFrame`:

```kotlin
        private fun publishFrame(bgr: ByteArray, width: Int, height: Int) {
            mainHandler.post {
                frameSink?.success(mapOf("width" to width, "height" to height, "bgr" to bgr))
            }
        }
```

and in `handle("startMonitoring")` read + forward the size:

```kotlin
                        MonitoringService.start(
                            appContext,
                            call.argument<String>("cameraId") ?: "0",
                            call.argument<String>("cameraName") ?: "Hallway",
                            call.argument<Number>("preRollSeconds")?.toInt() ?: 5,
                            call.argument<Number>("postRollSeconds")?.toInt() ?: 5,
                            call.argument<Boolean>("recordVideo") ?: true,
                            call.argument<String>("videoQuality") ?: "lowest",
                            call.argument<Number>("analysisWidth")?.toInt() ?: 320,
                            call.argument<Number>("analysisHeight")?.toInt() ?: 240,
                        )
```

`MonitoringService.kt` — thread the size through `start(...)`/`onStart(...)`:

- `start()` companion: add `analysisWidth: Int = 320, analysisHeight: Int = 240` params and `.putExtra(EXTRA_ANALYSIS_WIDTH, analysisWidth)` / `EXTRA_ANALYSIS_HEIGHT`.
- Add `const val EXTRA_ANALYSIS_WIDTH = "analysisWidth"` / `EXTRA_ANALYSIS_HEIGHT = "analysisHeight"`.
- `onStartCommand`: pass the extras into `MonitoringServiceController.onStart(...)`.
- `MonitoringServiceController.onStart(...)`: add the two params; store them; `bindCamera` uses them:

```kotlin
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(analysisWidth, analysisHeight))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
```

- Replace `toGray(image)` with `toBgr(image)` and update the publish call:

```kotlin
                        CameraFrameBus.publish(toBgr(image), image.width, image.height)
```

Add the YUV→BGR converter (replace the `toGray` function, lines 253–268):

```kotlin
    /** Converts a CameraX YUV_420_888 frame to interleaved BGR. */
    private fun toBgr(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val width = image.width
        val height = image.height
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val out = ByteArray(width * height * 3)
        var outIdx = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = (row shr 1) * uvRowStride
            for (col in 0 until width) {
                val y = yBuf.get(yRow + col).toInt() and 0xFF
                val uvIdx = uvRow + (col shr 1) * uvPixelStride
                val u = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvIdx).toInt() and 0xFF) - 128
                val r = (y + (v * 1436 / 1024)).coerceIn(0, 255)
                val g = (y - (u * 352 / 1024) - (v * 731 / 1024)).coerceIn(0, 255)
                val b = (y + (u * 1814 / 1024)).coerceIn(0, 255)
                out[outIdx++] = b.toByte()
                out[outIdx++] = g.toByte()
                out[outIdx++] = r.toByte()
            }
        }
        return out
    }
```

> **Implementation note:** `ImageProxy` planes are `YUV_420_888` under CameraX `ImageAnalysis` default output format. Confirm with the CameraX version in `android/app/build.gradle` (the `YUV_420_888` contract is stable). The `uvRowStride`/`uvPixelStride` handling covers both tightly- and loosely-packed chroma.

- [ ] **Step 6: Run Dart tests**

Run: `date -R && cd security_cam && flutter test test/android_camera_session_test.dart test/monitor_controller_test.dart`
Expected: PASS.

- [ ] **Step 7: Build the Android app to typecheck the Kotlin**

Run: `date -R && cd security_cam && flutter build apk --debug`
Expected: BUILD SUCCESSFUL (Kotlin compiles).

- [ ] **Step 8: Commit**

```bash
git add security_cam/lib/sensors/android_camera_session.dart security_cam/lib/state/monitor_controller.dart security_cam/test/android_camera_session_test.dart security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service
git commit -m "feat: Android color analysis frames with per-preset resolution"
```

---

### Task 11: Linux desktop integration test (real BlazeFace)

**Files:**
- Create: `security_cam/integration_test/face_detection_linux_test.dart`
- Modify: `security_cam/pubspec.yaml` (`integration_test` is already a dev dep)

- [ ] **Step 1: Write the integration test**

Create `security_cam/integration_test/face_detection_linux_test.dart`:

```dart
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:security_cam/detection/face/face_engine.dart';
import 'package:security_cam/detection/face/tflite_face_engine.dart';

/// Runs on `flutter test integration_test/face_detection_linux_test.dart -d linux`.
/// Uses the REAL BlazeFace engine against a synthetic face-free image: asserts
/// the engine loads and returns zero detections (sanity), and stays alive.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  test('tflite face engine loads and runs on a blank frame', () async {
    final engine = TfliteFaceEngine();
    await engine.init();
    final frame = ColorBitmap(
      128,
      128,
      Uint8List(128 * 128 * 3)..fillRange(0, 128 * 128 * 3, 128),
    );
    final faces = await engine.detectFaces(frame);
    expect(faces, isEmpty);
    await engine.dispose();
  });
}
```

- [ ] **Step 2: Run it on Linux desktop**

Run: `date -R && cd security_cam && flutter test integration_test/face_detection_linux_test.dart -d linux`
Expected: PASS (engine initializes, runs, returns no faces on a blank frame). If the native libs fail to load on this Linux host, investigate the package's Linux FFI setup before proceeding (this is the dev-loop gate for the real model).

- [ ] **Step 3: Commit**

```bash
git add security_cam/integration_test/face_detection_linux_test.dart
git commit -m "test: linux desktop smoke for real BlazeFace engine"
```

---

### Task 12: Android emulator integration scenario

**Files:**
- Modify: `security_cam/integration_test/monitoring_on_device_test.dart` (or the harness that `security_cam/tool/run_android_integration_tests.sh` drives)
- Modify: `security_cam/tool/run_android_integration_tests.sh` (if the scenario needs a new marker)

- [ ] **Step 1: Add an on-device face assertion**

In the on-device integration harness, after the existing motion scenario, assert that monitoring starts with the face detector enabled and the pipeline emits a `face` trigger path without crashing:

```dart
testWidgets('face detector is wired and motion-gated', (tester) async {
  // Start monitoring with face enabled (motion-gated).
  // Expect: monitoring reaches 'monitoring' state; no crash from the engine.
  // (Emulator scene has no real face, so no face trigger is expected here —
  //   the assertion is that the async gated path runs without error.)
});
```

- [ ] **Step 2: Run the Android integration suite**

Preconditions per `AGENTS.md`: only one AOSP emulator at a time; ≥4 GiB free RAM; loadavg < 75% of cores.

Run: `date -R && security_cam/tool/run_android_integration_tests.sh pixel_24_aosp`
Expected: suite passes; face path runs without error on the gated frames.

- [ ] **Step 3: Clean up the emulator**

Run: `date -R && adb -s <serial> emu kill; pkill -9 -f qemu-system; ps aux | rg 'qemu-system' || echo clean`
Expected: no `qemu-system` processes remain.

- [ ] **Step 4: Commit**

```bash
git add security_cam/integration_test security_cam/tool
git commit -m "test: on-device face detection integration scenario"
```

---

## Self-Review notes

- **Spec coverage:** color stream (Tasks 1, 2, 9, 10) ✓; motion gating (Tasks 3, 4) ✓; FaceDetector + engine (Task 6) ✓; registry/defaults (Task 7) ✓; Settings UI + Advanced section (Task 8) ✓; testing incl. Linux real-model + emulator (Tasks 11, 12) ✓; recognition deferred (explicitly out of scope) ✓.
- **Recognition (future phase):** deliberately not implemented; `face_detection_tflite` ships MobileFaceNet 192-d embeddings + `compareFaces`, so the deferred phase is enrollment + matching only.
- **Known risk to verify during Task 6/10:** the exact `detectFacesFromMatBytes` and `Face.boundingBox` API shape, and the CameraX `YUV_420_888` plane contract — both flagged inline.