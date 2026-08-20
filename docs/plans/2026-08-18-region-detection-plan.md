# Detection Regions (Inclusion Zones) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional user-drawn inclusion regions (ROI) to the analysis frame so all frame detectors (motion now; face/person later) only trigger inside them, with zero regions = today's whole-frame behavior.

**Architecture:** A new `DetectionRegion` model (normalized 0..1 coords, rect or poly, with a label) persists on `AppSettings`. A pure-Dart `RegionFilter` module does the geometry (point-in-region, any-overlap for boxes, a cached pixel mask for motion). The region set lives on `DetectorPipeline` and fans out to each `FrameDetector` (which gains an optional `regions` field); `MotionDetector` restricts its diff to the mask and `FaceDetector` drops boxes that don't overlap. A Settings editor (full-screen, live preview, rect/poly draw tools) saves regions; `CameraView` gets a toggleable overlay.

**Tech Stack:** Flutter/Dart, existing `CameraSession`/`CameraView` preview, existing settings store pattern.

**Spec:** `docs/plans/2026-08-18-region-detection-design.md`

---

### Task 1: `DetectionRegion` model + JSON

**Files:**
- Modify: `security_cam/lib/core/models.dart` (after `TriggerType`, ~line 72)
- Test: `security_cam/test/detection_region_test.dart` (Create)

- [ ] **Step 1: Write the failing test**

Create `security_cam/test/detection_region_test.dart`:

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/models.dart';

void main() {
  const rect = DetectionRegion(
    id: 'r1',
    shape: 'rect',
    label: 'doorway',
    points: [0.1, 0.2, 0.5, 0.8],
  );

  test('rect JSON round-trips', () {
    final back = DetectionRegion.fromJson(rect.toJson());
    expect(back.id, 'r1');
    expect(back.shape, 'rect');
    expect(back.label, 'doorway');
    expect(back.points, [0.1, 0.2, 0.5, 0.8]);
  });

  test('poly JSON round-trips', () {
    const poly = DetectionRegion(
      id: 'p1',
      shape: 'poly',
      label: 'driveway',
      points: [0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8],
    );
    final back = DetectionRegion.fromJson(poly.toJson());
    expect(back.shape, 'poly');
    expect(back.label, 'driveway');
    expect(back.points, [0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8]);
  });
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/detection_region_test.dart`
Expected: FAIL — `DetectionRegion` is not defined.

- [ ] **Step 3: Implement `DetectionRegion`**

Edit `security_cam/lib/core/models.dart`. Add after the `TriggerType` class (after line 72):

```dart
class DetectionRegion {
  /// Stable id for editing/delete targeting.
  final String id;

  /// 'rect' | 'poly' (see [DetectionRegionShape]).
  final String shape;

  /// User-friendly name shown in the editor list.
  final String label;

  /// Normalized 0..1 relative to the analysis frame, flattened. Rect:
  /// [x0,y0,x1,y1]. Poly: [x0,y0,x1,y1,...] vertex pairs.
  final List<double> points;

  const DetectionRegion({
    required this.id,
    required this.shape,
    required this.label,
    required this.points,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'shape': shape,
        'label': label,
        'points': points,
      };

  factory DetectionRegion.fromJson(Map<String, dynamic> json) =>
      DetectionRegion(
        id: json['id'] as String,
        shape: json['shape'] as String,
        label: json['label'] as String,
        points: (json['points'] as List).cast<double>(),
      );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/detection_region_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/core/models.dart security_cam/test/detection_region_test.dart
git commit -m "feat: add DetectionRegion model with JSON round-trip"
```

---

### Task 2: `AppSettings.detectionRegions` + JSON round-trip

**Files:**
- Modify: `security_cam/lib/core/settings.dart`
- Modify: `security_cam/test/settings_test.dart`

- [ ] **Step 1: Write the failing test**

Append to `security_cam/test/settings_test.dart`:

```dart
import 'package:security_cam/core/models.dart';

test('detection regions default to empty', () {
  final s = AppSettings.defaults();
  expect(s.detectionRegions, isEmpty);
});

test('detection regions JSON round-trip (rect + poly)', () {
  final s = AppSettings.defaults().copyWith(detectionRegions: const [
    DetectionRegion(
        id: 'r1', shape: 'rect', label: 'doorway', points: [0.1, 0.2, 0.5, 0.8]),
    DetectionRegion(
        id: 'p1',
        shape: 'poly',
        label: 'driveway',
        points: [0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8]),
  ]);
  final back = AppSettings.fromJson(s.toJson());
  expect(back.detectionRegions, hasLength(2));
  expect(back.detectionRegions[0].label, 'doorway');
  expect(back.detectionRegions[1].points,
      [0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8]);
});

test('old JSON without detection regions falls back to empty', () {
  final back = AppSettings.fromJson(const {});
  expect(back.detectionRegions, isEmpty);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/settings_test.dart`
Expected: FAIL — `detectionRegions` is not a member of `AppSettings`.

- [ ] **Step 3: Implement**

Edit `security_cam/lib/core/settings.dart`:

1. Field declaration after `analysisResolution` (line 115):

```dart
  /// Inclusion regions (normalized 0..1 on the analysis frame). Empty = detect
  /// everywhere (whole frame).
  final List<DetectionRegion> detectionRegions;
```

2. Constructor default (after line 131):

```dart
    this.detectionRegions = const [],
```

3. `copyWith` (after `String? analysisResolution,` line 198):

```dart
    List<DetectionRegion>? detectionRegions,
```

and in the returned `AppSettings`:

```dart
      detectionRegions: detectionRegions ?? this.detectionRegions,
```

4. `toJson` (after `'analysisResolution'` line 236):

```dart
        'detectionRegions': detectionRegions.map((r) => r.toJson()).toList(),
```

5. `fromJson` (after `analysisResolution` line 273):

```dart
      detectionRegions: (json['detectionRegions'] as List?)
              ?.map((e) =>
                  DetectionRegion.fromJson(e as Map<String, dynamic>))
              .toList() ??
          const [],
```

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/settings_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/core/settings.dart security_cam/test/settings_test.dart
git commit -m "feat: persist detection regions in app settings"
```

---

### Task 3: `RegionFilter` geometry module

**Files:**
- Create: `security_cam/lib/detection/regions/region_filter.dart`
- Test: `security_cam/test/region_filter_test.dart` (Create)

- [ ] **Step 1: Write the failing test**

Create `security_cam/test/region_filter_test.dart`:

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/regions/region_filter.dart';

void main() {
  const rect = DetectionRegion(
      id: 'r1', shape: 'rect', label: 'doorway', points: [0.1, 0.2, 0.5, 0.8]);

  const poly = DetectionRegion(
      id: 'p1',
      shape: 'poly',
      label: 'driveway',
      points: [0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8]);

  group('pointInRegion', () {
    test('rect contains interior point', () {
      expect(pointInRegion(rect, 0.3, 0.5), isTrue);
    });

    test('rect excludes outside point', () {
      expect(pointInRegion(rect, 0.7, 0.5), isFalse);
      expect(pointInRegion(rect, 0.3, 0.9), isFalse);
    });

    test('rect bounds are inclusive', () {
      expect(pointInRegion(rect, 0.1, 0.2), isTrue);
      expect(pointInRegion(rect, 0.5, 0.8), isTrue);
    });

    test('convex poly contains interior point', () {
      expect(pointInRegion(poly, 0.7, 0.45), isTrue);
    });

    test('poly excludes outside point', () {
      expect(pointInRegion(poly, 0.6, 0.1), isFalse);
    });

    test('concave poly ray-casting', () {
      // L-shaped poly: the notch area must be outside.
      const l = DetectionRegion(
          id: 'l1',
          shape: 'poly',
          label: 'L',
          points: [0.2, 0.2, 0.8, 0.2, 0.8, 0.4, 0.5, 0.4, 0.5, 0.8, 0.2, 0.8]);
      expect(pointInRegion(l, 0.3, 0.6), isTrue);  // inside the L
      expect(pointInRegion(l, 0.65, 0.6), isFalse); // in the notch
    });
  });

  group('rectOverlapsAny', () {
    test('empty regions = whole frame (always overlap)', () {
      expect(rectOverlapsAny(const [], 0.0, 0.0, 0.1, 0.1), isTrue);
    });

    test('box fully inside a region', () {
      expect(rectOverlapsAny(const [rect], 0.2, 0.3, 0.1, 0.1), isTrue);
    });

    test('box crossing a region edge', () {
      expect(rectOverlapsAny(const [rect], 0.45, 0.7, 0.1, 0.2), isTrue);
    });

    test('box merely touching a corner counts as overlap', () {
      expect(rectOverlapsAny(const [rect], 0.5, 0.8, 0.05, 0.05), isTrue);
    });

    test('box outside all regions', () {
      expect(rectOverlapsAny(const [rect], 0.7, 0.7, 0.1, 0.1), isFalse);
    });

    test('box overlapping a poly region', () {
      expect(rectOverlapsAny(const [poly], 0.6, 0.35, 0.1, 0.1), isTrue);
    });
  });

  group('pixelMask', () {
    test('all-ones mask for empty regions, pixelCount = full area', () {
      final (mask, count) = pixelMask(const [], 4, 3);
      expect(mask, List.filled(12, 1));
      expect(count, 12);
    });

    test('union of overlapping regions counted once', () {
      const a = DetectionRegion(
          id: 'a', shape: 'rect', label: 'a', points: [0.0, 0.0, 0.5, 0.5]);
      const b = DetectionRegion(
          id: 'b', shape: 'rect', label: 'b', points: [0.25, 0.25, 0.75, 0.75]);
      final (mask, count) = pixelMask(const [a, b], 4, 4);
      // Pixel centers at (x+0.5)/4, (y+0.5)/4:
      //   row0 (y=0.125): centers x=0.125(a),0.375(a/b),0.625(b only at
      //        y>=0.25 -> no),0.875(no)        -> 1,1,0,0
      //   row1 (y=0.375): x=0.125(a),0.375(b),0.625(b) -> 1,1,1,0
      //   row2 (y=0.625): x=0.125(no),0.375(b),0.625(b) -> 0,1,1,0
      //   row3 (y=0.875): all no                  -> 0,0,0,0
      expect(mask, [1, 1, 0, 0, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0]);
      expect(count, 8, reason: 'union = 8 px, overlapping a/b cells counted once');
    });
  });
}
```

> Note: `pixelMask` returns a Dart record `(Uint8List, int)`. The union test above
> assumes pixels are marked by their **center** falling inside a region (pixel
> (0,0) center = (0.125, 0.125), inside both `a` and `b`). If the record syntax
> `final (mask, count) = ...` is unfamiliar, it is a Dart 3 pattern — the codebase
> already uses `(int, int)` records (e.g. `AnalysisResolution.size`).

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/region_filter_test.dart`
Expected: FAIL — `RegionFilter`/`region_filter.dart` not found.

- [ ] **Step 3: Implement `RegionFilter`**

Create `security_cam/lib/detection/regions/region_filter.dart`:

```dart
import 'dart:typed_data';

import '../../core/models.dart';

/// Whether [p] is inside region [r]. Coordinates are normalized 0..1.
bool pointInRegion(DetectionRegion r, double x, double y) {
  if (r.shape == DetectionRegionShape.rect) {
    final x0 = r.points[0], y0 = r.points[1];
    final x1 = r.points[2], y1 = r.points[3];
    return x >= x0 && x <= x1 && y >= y0 && y <= y1;
  }
  return _pointInPolygon(x, y, r.points);
}

/// Ray-casting point-in-polygon over a flattened [x0,y0,x1,y1,...] list.
bool _pointInPolygon(double x, double y, List<double> pts) {
  var inside = false;
  var j = pts.length - 2;
  for (var i = 0; i < pts.length; i += 2) {
    final xi = pts[i], yi = pts[i + 1];
    final xj = pts[j], yj = pts[j + 1];
    final intersects =
        ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
    if (intersects) inside = !inside;
    j = i;
  }
  return inside;
}

/// True when box (x, y, w, h) overlaps ANY region (the "any overlap" rule).
/// Empty [regions] = whole frame → always true.
bool rectOverlapsAny(
    List<DetectionRegion> regions, double x, double y, double w, double h) {
  if (regions.isEmpty) return true;
  final corners = [
    (x, y),
    (x + w, y),
    (x, y + h),
    (x + w, y + h),
  ];
  for (final r in regions) {
    if (_boxOverlapsRegion(r, x, y, w, h, corners)) return true;
  }
  return false;
}

bool _boxOverlapsRegion(DetectionRegion r, double x, double y, double w, double h,
    List<(double, double)> corners) {
  if (r.shape == DetectionRegionShape.rect) {
    final x0 = r.points[0], y0 = r.points[1];
    final x1 = r.points[2], y1 = r.points[3];
    // Inclusive edges: a box merely touching the region's border counts as
    // overlap (matches "box merely touching a corner counts as overlap").
    return x <= x1 && x + w >= x0 && y <= y1 && y + h >= y0;
  }
  // Poly: any box corner inside the poly, OR any poly vertex inside the box,
  // OR any box edge intersecting any poly edge.
  for (final (cx, cy) in corners) {
    if (_pointInPolygon(cx, cy, r.points)) return true;
  }
  final boxX0 = x, boxY0 = y, boxX1 = x + w, boxY1 = y + h;
  for (var i = 0; i < r.points.length; i += 2) {
    final vx = r.points[i], vy = r.points[i + 1];
    if (vx >= boxX0 && vx <= boxX1 && vy >= boxY0 && vy <= boxY1) return true;
  }
  // Box edge vs poly edge intersection (cross-boundary case).
  final boxEdges = [
    ((boxX0, boxY0), (boxX1, boxY0)),
    ((boxX1, boxY0), (boxX1, boxY1)),
    ((boxX1, boxY1), (boxX0, boxY1)),
    ((boxX0, boxY1), (boxX0, boxY0)),
  ];
  var j = r.points.length - 2;
  for (var i = 0; i < r.points.length; i += 2) {
    final (xi, yi) = (r.points[i], r.points[i + 1]);
    final (xj, yj) = (r.points[j], r.points[j + 1]);
    for (final (p1, p2) in boxEdges) {
      if (_segmentsIntersect(p1, p2, (xi, yi), (xj, yj))) return true;
    }
    j = i;
  }
  return false;
}

bool _segmentsIntersect(
    (double, double) p1, (double, double) p2,
    (double, double) p3, (double, double) p4) {
  double orient((double, double) a, (double, double) b, (double, double) c) =>
      (b.$1 - a.$1) * (c.$2 - a.$2) - (b.$2 - a.$2) * (c.$1 - a.$1);

  bool onSegment((double, double) a, (double, double) b, (double, double) c) =>
      b.$1 <= a.$1.max(c.$1) && b.$1 >= a.$1.min(c.$1) &&
      b.$2 <= a.$2.max(c.$2) && b.$2 >= a.$2.min(c.$2);

  final o1 = orient(p1, p2, p3);
  final o2 = orient(p1, p2, p4);
  final o3 = orient(p3, p4, p1);
  final o4 = orient(p3, p4, p2);

  // Proper intersection: p3/p4 straddle line p1p2 AND p1/p2 straddle line p3p4.
  if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) &&
      ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) {
    return true;
  }
  // Collinear special cases.
  if (o1 == 0 && onSegment(p1, p3, p2)) return true;
  if (o2 == 0 && onSegment(p1, p4, p2)) return true;
  if (o3 == 0 && onSegment(p3, p1, p4)) return true;
  if (o4 == 0 && onSegment(p3, p2, p4)) return true;
  return false;
}

/// Builds a byte mask (1 = inside ANY region) and the count of 1-bits, using
/// each pixel's center for the point-in-region test. Empty [regions] → all ones.
(Uint8List, int) pixelMask(List<DetectionRegion> regions, int width, int height) {
  final mask = Uint8List(width * height);
  if (regions.isEmpty) {
    mask.fillRange(0, mask.length, 1);
    return (mask, mask.length);
  }
  var count = 0;
  for (var y = 0; y < height; y++) {
    for (var x = 0; x < width; x++) {
      final nx = (x + 0.5) / width;
      final ny = (y + 0.5) / height;
      var inside = false;
      for (final r in regions) {
        if (pointInRegion(r, nx, ny)) {
          inside = true;
          break;
        }
      }
      if (inside) {
        mask[y * width + x] = 1;
        count++;
      }
    }
  }
  return (mask, count);
}
```

- [ ] **Step 4: Add `DetectionRegionShape` pseudo-enum**

Edit `security_cam/lib/core/models.dart`, add after the `DetectionRegion` class:

```dart
class DetectionRegionShape {
  static const rect = 'rect';
  static const poly = 'poly';
  static const values = [rect, poly];

  const DetectionRegionShape._();
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/region_filter_test.dart`
Expected: PASS.

> If any polygon edge-intersection case fails, check `_segmentsIntersect`: it
> tests whether p3/p4 straddle line p1p2 AND p1/p2 straddle line p3p4 via the
> four orientation signs (`o1`..`o4`), with collinear on-segment fallbacks. The
> tests above exercise the common cases; fix only if red.

- [ ] **Step 6: Commit**

```bash
git add security_cam/lib/core/models.dart security_cam/lib/detection/regions/region_filter.dart security_cam/test/region_filter_test.dart
git commit -m "feat: region geometry (point-in-region, any-overlap, pixel mask)"
```

---

### Task 4: Region set on the pipeline + `FrameDetector.regions`

**Files:**
- Modify: `security_cam/lib/core/detector.dart` (`FrameDetector`, lines 79–86)
- Modify: `security_cam/lib/detection/pipeline.dart`
- Modify: `security_cam/lib/state/monitor_controller.dart:160-164`
- Modify: `security_cam/test/pipeline_test.dart`

- [ ] **Step 1: Write the failing test**

Append to `security_cam/test/pipeline_test.dart` (reuse the file's existing imports; add `import 'package:security_cam/core/models.dart';` if absent):

```dart
test('setRegions fans out to frame detectors', () async {
  final pipeline = DetectorPipeline(
    classifier: MockAudioEventClassifier(),
    configs: const [
      DetectorConfig(type: TriggerType.motion, enabled: true, threshold: 0.01),
    ],
  );
  await pipeline.init();
  final motion = pipeline.frameDetectors.first as MotionDetector;
  expect(motion.regions, isEmpty);

  const region = DetectionRegion(
      id: 'r1', shape: 'rect', label: 'doorway', points: [0.1, 0.2, 0.5, 0.8]);
  pipeline.setRegions(const [region]);
  expect(motion.regions, [region]);
  await pipeline.dispose();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/pipeline_test.dart`
Expected: FAIL — `regions` is not a member of `FrameDetector` / `MotionDetector`.

- [ ] **Step 3: Implement**

Edit `security_cam/lib/core/detector.dart`, `FrameDetector`:

```dart
abstract class FrameDetector extends Detector {
  /// Inclusion regions (normalized 0..1 on the analysis frame). Empty = detect
  /// everywhere. Set by the pipeline via [DetectorPipeline.setRegions].
  List<DetectionRegion> regions = const [];

  DetectionResult analyzeFrame(AnalysisFrame frame);

  /// Async analysis path for gated/heavy detectors (runs off the pipeline's
  /// sync per-frame loop). Defaults to the sync path wrapped in a Future.
  Future<DetectionResult> analyzeFrameAsync(AnalysisFrame frame) async =>
      analyzeFrame(frame);
}
```

Edit `security_cam/lib/detection/pipeline.dart`, add after `debugAddFrameDetector` (line 22):

```dart
  /// Sets the global inclusion regions and fans them out to every frame
  /// detector. Empty = detect everywhere.
  void setRegions(List<DetectionRegion> regions) {
    for (final d in _frameDetectors) {
      d.regions = regions;
    }
  }
```

Edit `security_cam/lib/state/monitor_controller.dart` — in `start()`, right after `await pipeline.init();` (line 164), add:

```dart
      pipeline.setRegions(settings.detectionRegions);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/pipeline_test.dart test/monitor_controller_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/core/detector.dart security_cam/lib/detection/pipeline.dart security_cam/lib/state/monitor_controller.dart security_cam/test/pipeline_test.dart
git commit -m "feat: fan inclusion regions out to frame detectors via pipeline"
```

---

### Task 5: `MotionDetector` region-restricted diff

**Files:**
- Modify: `security_cam/lib/detection/motion_detector.dart`
- Modify: `security_cam/test/motion_detector_test.dart`

- [ ] **Step 1: Write the failing tests**

Append to `security_cam/test/motion_detector_test.dart`:

```dart
import 'package:security_cam/core/models.dart';

test('change inside a region triggers; same change outside does not', () {
  detector.regions = const [
    DetectionRegion(
        id: 'r1', shape: 'rect', label: 'doorway', points: [0.0, 0.0, 0.5, 0.5]),
  ];
  // Outside case: move a rect only in the bottom-right quadrant (outside the
  // [0,0.5]x[0,0.5] region). Region-relative ratio stays 0 -> never triggers.
  detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16));
  detector.analyzeFrame(
      frame(1, buildFrameWithRect(16, 16, 140, 8, 8, 8, 8, 30), 16, 16));
  detector.analyzeFrame(
      frame(2, buildFrameWithRect(16, 16, 140, 10, 10, 8, 8, 30), 16, 16));
  expect(detector.analyzeFrame(
          frame(3, buildFrameWithRect(16, 16, 140, 12, 12, 8, 8, 30), 16, 16))
      .triggered, isFalse);

  // Inside case: move a rect within the top-left quadrant (inside the region).
  // Two consecutive above-threshold diffs (4x4=16 px / 64 region px = 0.25) arm
  // the persistence counter.
  detector.reset();
  detector.analyzeFrame(frame(4, buildFrame(16, 16, 140), 16, 16));
  detector.analyzeFrame(
      frame(5, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16));
  expect(detector.analyzeFrame(
          frame(6, buildFrameWithRect(16, 16, 140, 4, 4, 4, 4, 30), 16, 16))
      .triggered, isTrue);
});

test('small region denominator keeps thresholds meaningful', () {
  detector = MotionDetector(config(threshold: 0.2, persistence: 2));
  // Region = top-left quadrant (64 of 256 px). A moving 4x4 rect: two
  // consecutive diffs of ~16/64 = 0.25 > 0.2 -> triggers. With a full-frame
  // denominator (16/256 = 0.0625) it never would, asserting the region-area
  // denominator.
  detector.regions = const [
    DetectionRegion(
        id: 'r1', shape: 'rect', label: 'q1', points: [0.0, 0.0, 0.5, 0.5]),
  ];
  detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16));
  detector.analyzeFrame(frame(1, buildFrameWithRect(16, 16, 140, 1, 1, 4, 4, 30), 16, 16));
  expect(detector.analyzeFrame(frame(2, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16))
      .triggered, isTrue);
});

test('empty regions = legacy whole-frame behavior', () {
  expect(
      detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16)).triggered,
      isFalse);
  detector.analyzeFrame(frame(1, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16));
  expect(detector.analyzeFrame(frame(2, buildFrameWithRect(16, 16, 140, 4, 4, 4, 4, 30), 16, 16))
      .triggered, isTrue);
});
```

> Note: `buildFrameWithRect(16, 16, 140, 8, 8, 8, 8, 30)` changes the bottom-right
> 8×8 quadrant (rows 8–15), which is outside the `[0,0,0.5,0.5]` region. The
> region-relative diff ratio for the outside change is 0 (denominator = 64), so
> it can never trigger. Inside-region changes use denominator 64 as asserted.

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/motion_detector_test.dart`
Expected: FAIL — `regions` has no effect yet (all tests still use full-frame diff).

- [ ] **Step 3: Implement**

Edit `security_cam/lib/detection/motion_detector.dart`:

1. Add import:

```dart
import 'dart:typed_data';

import 'package:flutter/foundation.dart';

import '../core/detector.dart';
import '../core/models.dart';
import 'regions/region_filter.dart';
```

2. Add fields (after `_persistenceCount`):

```dart
  Uint8List? _mask;
  int _maskCount = 0;
  int _maskWidth = 0;
  int _maskHeight = 0;
  List<DetectionRegion>? _maskRegions;
```

3. In `analyzeFrame`, rebuild the mask when regions or frame size change (before `final bitmap = frame.bitmap;`):

```dart
    if (_mask == null ||
        !identical(_maskRegions, regions) ||
        _maskWidth != frame.bitmap.width ||
        _maskHeight != frame.bitmap.height) {
      _rebuildMask(frame.bitmap.width, frame.bitmap.height);
    }
```

4. Add `_rebuildMask` and change `_diffRatio`:

```dart
  void _rebuildMask(int width, int height) {
    final (mask, count) = pixelMask(regions, width, height);
    _mask = mask;
    _maskCount = count;
    _maskRegions = regions;
    _maskWidth = width;
    _maskHeight = height;
  }

  double _diffRatio(GrayscaleBitmap a, GrayscaleBitmap b) {
    final mask = _mask!;
    final count = _maskCount;
    var changed = 0;
    for (var y = 0; y < a.height; y++) {
      final rowA = y * a.width;
      final rowB = y * a.width;
      for (var x = 0; x < a.width; x++) {
        final idx = rowA + x;
        if (mask[idx] == 0) continue;
        final diff = (a.gray[idx] - b.gray[idx]).abs();
        if (diff > _pixelDiffTolerance) changed++;
      }
    }
    return count == 0 ? 0.0 : changed / count;
  }
```

5. In `reset()`, invalidate the mask cache:

```dart
  @override
  void reset() {
    _previous = null;
    _persistenceCount = 0;
    _mask = null;
  }
```

> Note: `_rebuildMask` caches per (regions list identity, frame size). The
> pipeline assigns a fresh list via `setRegions`, so `identical` catches region
> edits; `reset()` clears the cache; size changes are caught by the width/height
> check. `MotionDetector.reset()` is called by the pipeline on `reset()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/motion_detector_test.dart test/pipeline_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/detection/motion_detector.dart security_cam/test/motion_detector_test.dart
git commit -m "feat: motion detector respects inclusion regions (region-area diff)"
```

---

### Task 6: `FaceDetector` region hook (any-overlap)

**Files:**
- Modify: `security_cam/lib/detection/face/face_detector.dart`
- Modify: `security_cam/test/face_detector_test.dart`

- [ ] **Step 1: Write the failing tests**

Append to `security_cam/test/face_detector_test.dart` (existing imports cover `models.dart`; the filter logic is exercised via `FaceDetector`, not directly):

```dart
// Rect region covering the left half of the frame.
const _halfRegion = DetectionRegion(
    id: 'r1', shape: 'rect', label: 'left', points: [0.0, 0.0, 0.5, 1.0]);

test('face outside all regions does not trigger', () async {
  // Frame is 3x3 px. Box (1.7,0.4)-(2.8,0.6) -> normalized x 0.567..0.933,
  // outside the left-half region [0,0.5]x[0,1].
  final engine = MockFaceEngine()
    ..faces.add(const FaceDetection(
        box: (1.7, 0.4, 2.8, 0.6), score: 0.9));
  final d = FaceDetector(
    const DetectorConfig(type: TriggerType.face, threshold: 0.5, persistenceFrames: 1),
    engine: engine,
  );
  d.regions = const [_halfRegion];
  await d.init();
  final r = await d.analyzeFrameAsync(frame(base));
  expect(r.triggered, isFalse);
  await d.dispose();
});

test('face overlapping a region triggers', () async {
  // Box (1.2,0.4)-(2.4,0.6) -> normalized x 0.4..0.8, crosses the x=0.5 edge.
  final engine = MockFaceEngine()
    ..faces.add(const FaceDetection(
        box: (1.2, 0.4, 2.4, 0.6), score: 0.9));
  final d = FaceDetector(
    const DetectorConfig(type: TriggerType.face, threshold: 0.5, persistenceFrames: 1),
    engine: engine,
  );
  d.regions = const [_halfRegion];
  await d.init();
  final r = await d.analyzeFrameAsync(frame(base));
  expect(r.triggered, isTrue);
  await d.dispose();
});

test('empty regions = all faces pass', () async {
  final engine = MockFaceEngine()
    ..faces.add(const FaceDetection(
        box: (0.9, 0.1, 0.95, 0.2), score: 0.9));
  final d = FaceDetector(
    const DetectorConfig(type: TriggerType.face, threshold: 0.5, persistenceFrames: 1),
    engine: engine,
  );
  await d.init();
  final r = await d.analyzeFrameAsync(frame(base));
  expect(r.triggered, isTrue);
  await d.dispose();
});
```

> Note: `FaceDetection.box` is a `FaceBox` record `(x, y, x2, y2)` in **pixel**
> coordinates of the color frame. The `frame(base)` helper in this file builds a
> 3×3 color frame, so box coordinates above are pixel coords; the detector
> normalizes them against the frame's width/height before the overlap test.

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/face_detector_test.dart`
Expected: FAIL — the two new region tests trigger when they shouldn't / don't (regions unused).

- [ ] **Step 3: Implement**

Edit `security_cam/lib/detection/face/face_detector.dart`:

1. Add import:

```dart
import '../regions/region_filter.dart';
```

2. In `analyzeFrameAsync`, after `final faces = await _engine.detectFaces(color);`, filter by regions before the emptiness check:

```dart
    var faces = await _engine.detectFaces(color);
    if (regions.isNotEmpty) {
      faces = [
        for (final f in faces)
          if (rectOverlapsAny(
            regions,
            f.box.$1 / color.width,
            f.box.$2 / color.height,
            (f.box.$3 - f.box.$1) / color.width,
            (f.box.$4 - f.box.$2) / color.height,
          ))
            f,
      ];
    }
    if (faces.isEmpty) {
      _persistenceCount = 0;
      return _result(frame.timestamp, 0, false);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `date -R && cd security_cam && flutter test test/face_detector_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/detection/face/face_detector.dart security_cam/test/face_detector_test.dart
git commit -m "feat: face detector honors inclusion regions (any-overlap)"
```

---

### Task 7: Region editor screen + Settings entry

**Files:**
- Create: `security_cam/lib/ui/region_editor_screen.dart`
- Modify: `security_cam/lib/ui/settings_screen.dart`
- Test: `security_cam/test/region_editor_screen_test.dart` (Create)
- Modify: `security_cam/test/settings_screen_test.dart`

- [ ] **Step 1: Write the failing widget tests**

Create `security_cam/test/region_editor_screen_test.dart`:

```dart
import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:security_cam/core/models.dart';
import 'package:security_cam/ui/region_editor_screen.dart';

void main() {
  Stream<AnalysisFrame> frames() {
    final c = StreamController<AnalysisFrame>.broadcast();
    c.add(AnalysisFrame(
      timestamp: DateTime(2026),
      bitmap: GrayscaleBitmap(2, 2, Uint8List.fromList([140, 140, 140, 140])),
    ));
    return c.stream;
  }

  testWidgets('renders tool bar and region list', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: RegionEditorScreen(
        frames: frames(),
        initialRegions: const [
          DetectionRegion(
              id: 'r1', shape: 'rect', label: 'doorway', points: [0.1, 0.2, 0.5, 0.8]),
        ],
        onSave: (_) {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Detection regions'), findsOneWidget);
    expect(find.text('Rectangle'), findsOneWidget);
    expect(find.text('Polygon'), findsOneWidget);
    expect(find.text('doorway'), findsOneWidget);
  });

  testWidgets('Done saves the region list', (tester) async {
    List<DetectionRegion>? saved;
    await tester.pumpWidget(MaterialApp(
      home: RegionEditorScreen(
        frames: frames(),
        initialRegions: const [
          DetectionRegion(
              id: 'r1', shape: 'rect', label: 'doorway', points: [0.1, 0.2, 0.5, 0.8]),
        ],
        onSave: (r) => saved = r,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Done'));
    await tester.pumpAndSettle();
    expect(saved, isNotNull);
    expect(saved!.single.label, 'doorway');
  });

  testWidgets('Clear all removes regions (with confirm)', (tester) async {
    List<DetectionRegion>? saved;
    await tester.pumpWidget(MaterialApp(
      home: RegionEditorScreen(
        frames: frames(),
        initialRegions: const [
          DetectionRegion(
              id: 'r1', shape: 'rect', label: 'doorway', points: [0.1, 0.2, 0.5, 0.8]),
        ],
        onSave: (r) => saved = r,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Clear'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Clear').last); // confirm dialog button
    await tester.pumpAndSettle();
    expect(find.text('doorway'), findsNothing);
    expect(saved, isNull); // not saved until Done
    await tester.tap(find.text('Done'));
    await tester.pumpAndSettle();
    expect(saved, isEmpty);
  });
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/region_editor_screen_test.dart`
Expected: FAIL — `RegionEditorScreen` not defined.

- [ ] **Step 3: Implement the editor screen**

Create `security_cam/lib/ui/region_editor_screen.dart`:

```dart
import 'dart:async';

import 'package:flutter/material.dart';

import '../core/models.dart';
import '../detection/regions/region_filter.dart';
import 'widgets/camera_view.dart';

/// Full-screen inclusion-region editor: draws rects/polys over a live analysis
/// preview, then reports the final list via [onSave] on Done.
class RegionEditorScreen extends StatefulWidget {
  final Stream<AnalysisFrame> frames;
  final List<DetectionRegion> initialRegions;
  final ValueChanged<List<DetectionRegion>> onSave;

  const RegionEditorScreen({
    super.key,
    required this.frames,
    required this.initialRegions,
    required this.onSave,
  });

  @override
  State<RegionEditorScreen> createState() => _RegionEditorScreenState();
}

class _RegionEditorScreenState extends State<RegionEditorScreen> {
  late List<DetectionRegion> _regions;
  String _shape = DetectionRegionShape.rect;
  int _selected = -1;
  int _nextId = 1;
  List<double>? _pendingPoly;
  Size _previewSize = Size.zero;
  Offset? _dragStart;
  Offset? _dragLast;
  List<double>? _dragRect; // normalized [x0,y0,x1,y1] while dragging a new rect
  bool _dragResizing = false;
  bool _dragMoving = false;
  final TextEditingController _labelController = TextEditingController();

  static const _palette = [
    Color(0xFF8AB4F8),
    Color(0xFF81C995),
    Color(0xFFFDD663),
    Color(0xFFF28B82),
    Color(0xFFD7AEFB),
  ];

  @override
  void initState() {
    super.initState();
    _regions = List.of(widget.initialRegions);
  }

  @override
  void dispose() {
    _labelController.dispose();
    super.dispose();
  }

  /// Selects a region and syncs the label field with its name.
  void _select(int index) {
    setState(() {
      _selected = index;
      if (index >= 0 && index < _regions.length) {
        _labelController.text = _regions[index].label;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Detection regions'),
        actions: [
          TextButton(
            onPressed: () {
              widget.onSave(List.of(_regions));
              Navigator.of(context).maybePop();
            },
            child: const Text('Done'),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: LayoutBuilder(
                  builder: (context, constraints) {
                    _previewSize = constraints.biggest;
                    return ClipRect(
                      child: GestureDetector(
                        onTapUp: _onTap,
                        onPanStart: _onPanStart,
                        onPanUpdate: _onPanUpdate,
                        onPanEnd: _onPanEnd,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            CameraView(frames: widget.frames),
                            CustomPaint(
                              size: Size.infinite,
                              painter: _RegionPainter(
                                regions: _regions,
                                pendingPoly: _pendingPoly,
                                dragRect: _dragRect,
                                selected: _selected,
                                palette: _palette,
                              ),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                children: [
                  Row(
                    children: [
                      _toolButton('Rectangle', _shape == DetectionRegionShape.rect,
                          () => setState(() {
                                _shape = DetectionRegionShape.rect;
                                _pendingPoly = null;
                              })),
                      const SizedBox(width: 8),
                      _toolButton('Polygon', _shape == DetectionRegionShape.poly,
                          () => setState(() => _shape = DetectionRegionShape.poly)),
                      const SizedBox(width: 8),
                      if (_pendingPoly != null) ...[
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: _commitPoly,
                            icon: const Icon(Icons.check),
                            label: const Text('Close poly'),
                          ),
                        ),
                        const SizedBox(width: 8),
                      ],
                      OutlinedButton.icon(
                        onPressed: () => _addRegion(),
                        icon: const Icon(Icons.add),
                        label: const Text('Add'),
                      ),
                      const SizedBox(width: 8),
                      OutlinedButton.icon(
                        onPressed: () => _confirmClear(),
                        icon: const Icon(Icons.delete_forever),
                        label: const Text('Clear'),
                      ),
                    ],
                  ),
                  if (_regions.isNotEmpty)
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxHeight: 140),
                      child: ListView.separated(
                        shrinkWrap: true,
                        itemCount: _regions.length,
                        separatorBuilder: (_, __) =>
                            const Divider(height: 1),
                        itemBuilder: (context, i) => ListTile(
                          dense: true,
                          contentPadding: EdgeInsets.zero,
                          leading: Icon(Icons.crop_square,
                              color: _palette[i % _palette.length]),
                          title: Text(_regions[i].label),
                          subtitle: Text(_regions[i].shape),
                          selected: i == _selected,
                          onTap: () => _select(i),
                          trailing: IconButton(
                            icon: const Icon(Icons.close, size: 18),
                            tooltip: 'Delete region',
                            onPressed: () => setState(() {
                              _regions.removeAt(i);
                              if (_selected == i) {
                                _selected = -1;
                                _labelController.clear();
                              } else if (_selected > i) {
                                _selected--;
                              }
                            }),
                          ),
                        ),
                      ),
                    ),
                  if (_selected >= 0 && _selected < _regions.length)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Row(
                        children: [
                          Expanded(
                            child: TextField(
                              controller: _labelController,
                              decoration: const InputDecoration(
                                labelText: 'Region name',
                                isDense: true,
                                border: OutlineInputBorder(),
                              ),
                              onChanged: (v) => setState(() {
                                final r = _regions[_selected];
                                _regions[_selected] = DetectionRegion(
                                  id: r.id,
                                  shape: r.shape,
                                  label: v.trim().isEmpty ? r.label : v.trim(),
                                  points: r.points,
                                );
                              }),
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.delete_outline),
                            tooltip: 'Delete region',
                            onPressed: () => setState(() {
                              _regions.removeAt(_selected);
                              _selected = -1;
                              _labelController.clear();
                            }),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _toolButton(String label, bool active, VoidCallback onTap) {
    return OutlinedButton(
      style: OutlinedButton.styleFrom(
        backgroundColor: active ? Theme.of(context).colorScheme.primaryContainer : null,
      ),
      onPressed: onTap,
      child: Text(label),
    );
  }

  Offset _toNorm(Offset p) {
    final s = _previewSize;
    return Offset(
      (p.dx / s.width).clamp(0.0, 1.0),
      (p.dy / s.height).clamp(0.0, 1.0),
    );
  }

  void _onTapUpGlobal(Offset pos) {
    if (_shape == DetectionRegionShape.poly) {
      final n = _toNorm(pos);
      // First tap in poly mode STARTS the pending polygon.
      _pendingPoly ??= <double>[];
      _pendingPoly!.addAll([n.dx, n.dy]);
      setState(() {});
      return;
    }
    // Select region under tap (or deselect).
    _select(_hitRegion(_toNorm(pos)));
  }

  void _onTap(TapUpDetails d) => _onTapUpGlobal(d.localPosition);

  int _hitRegion(Offset n) {
    for (var i = _regions.length - 1; i >= 0; i--) {
      if (pointInRegion(_regions[i], n.dx, n.dy)) return i;
    }
    return -1;
  }

  void _onPanStart(DragStartDetails d) {
    final n = _toNorm(d.localPosition);
    final hit = _hitRegion(n);
    if (hit >= 0) {
      _select(hit);
      final r = _regions[hit];
      if (r.shape == DetectionRegionShape.rect && _nearCorner(r, n.dx, n.dy)) {
        _dragResizing = true;
      } else {
        _dragMoving = true;
      }
    } else if (_shape == DetectionRegionShape.rect) {
      // Start a new rectangle at the drag origin.
      setState(() {
        _selected = -1;
        _pendingPoly = null;
        _dragStart = Offset(n.dx, n.dy);
        _dragLast = Offset(n.dx, n.dy);
        _dragRect = [n.dx, n.dy, n.dx, n.dy];
      });
    }
  }

  void _onPanUpdate(DragUpdateDetails d) {
    final n = _toNorm(d.localPosition);
    setState(() {
      if (_dragResizing) {
        final r = _regions[_selected];
        _regions[_selected] = DetectionRegion(
          id: r.id,
          shape: r.shape,
          label: r.label,
          points: [r.points[0], r.points[1], n.dx, n.dy],
        );
      } else if (_dragMoving) {
        final r = _regions[_selected];
        final dx = n.dx - _dragLast!.dx;
        final dy = n.dy - _dragLast!.dy;
        _regions[_selected] = DetectionRegion(
          id: r.id,
          shape: r.shape,
          label: r.label,
          points: _translate(r.points, dx, dy),
        );
        _dragLast = n;
      } else if (_dragRect != null) {
        _dragLast = n;
        final (x0, y0) = (_dragStart!.dx, _dragStart!.dy);
        _dragRect = [
          x0 < n.dx ? x0 : n.dx,
          y0 < n.dy ? y0 : n.dy,
          x0 > n.dx ? x0 : n.dx,
          y0 > n.dy ? y0 : n.dy,
        ];
      }
    });
  }

  void _onPanEnd(DragEndDetails d) {
    final rect = _dragRect;
    setState(() {
      _dragStart = null;
      _dragLast = null;
      _dragResizing = false;
      _dragMoving = false;
      _dragRect = null;
    });
    // Commit the newly drawn rectangle (skipped for tiny drags).
    if (rect != null && (rect[2] - rect[0]).abs() >= 0.02 &&
        (rect[3] - rect[1]).abs() >= 0.02) {
      _regions.add(DetectionRegion(
        id: 'r${_nextId++}',
        shape: DetectionRegionShape.rect,
        label: 'Region ${_nextId - 1}',
        points: rect,
      ));
      _select(_regions.length - 1);
    }
  }

  List<double> _translate(List<double> pts, double dx, double dy) {
    final out = <double>[];
    for (var i = 0; i < pts.length; i += 2) {
      out
        ..add((pts[i] + dx).clamp(0.0, 1.0))
        ..add((pts[i + 1] + dy).clamp(0.0, 1.0));
    }
    return out;
  }

  bool _nearCorner(DetectionRegion r, double x, double y) {
    const tol = 0.06;
    final x0 = r.points[0], y0 = r.points[1];
    final x1 = r.points[2], y1 = r.points[3];
    return ((x - x0).abs() <= tol && (y - y0).abs() <= tol) ||
        ((x - x1).abs() <= tol && (y - y1).abs() <= tol);
  }

  void _addRegion() {
    _pendingPoly = null;
    _regions.add(DetectionRegion(
      id: 'r${_nextId++}',
      shape: DetectionRegionShape.rect,
      label: 'Region ${_nextId - 1}',
      points: const [0.2, 0.2, 0.8, 0.8],
    ));
    _select(_regions.length - 1);
  }

  void _commitPoly() {
    final p = _pendingPoly;
    _pendingPoly = null;
    if (p == null || p.length < 6) return;
    _regions.add(DetectionRegion(
      id: 'r${_nextId++}',
      shape: DetectionRegionShape.poly,
      label: 'Region ${_nextId - 1}',
      points: p,
    ));
    _select(_regions.length - 1);
  }

  Future<void> _confirmClear() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Clear all regions?'),
        content: const Text('This removes every inclusion region. Detection '
            'will apply to the whole frame.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Clear'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      setState(() {
        _regions.clear();
        _selected = -1;
        _pendingPoly = null;
      });
    }
  }
}

class _RegionPainter extends CustomPainter {
  final List<DetectionRegion> regions;
  final List<double>? pendingPoly;
  final List<double>? dragRect;
  final int selected;
  final List<Color> palette;

  _RegionPainter({
    required this.regions,
    required this.pendingPoly,
    required this.dragRect,
    required this.selected,
    required this.palette,
  });

  @override
  void paint(Canvas canvas, Size size) {
    for (var i = 0; i < regions.length; i++) {
      final r = regions[i];
      final paint = Paint()
        ..color = palette[i % palette.length].withValues(alpha: 0.18)
        ..style = PaintingStyle.fill;
      final stroke = Paint()
        ..color = palette[i % palette.length]
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..isAntiAlias = false;
      final isSelected = i == selected;
      if (r.shape == DetectionRegionShape.rect) {
        final x0 = r.points[0] * size.width, y0 = r.points[1] * size.height;
        final x1 = r.points[2] * size.width, y1 = r.points[3] * size.height;
        final rect = Rect.fromLTRB(x0, y0, x1, y1);
        canvas.drawRect(rect, paint);
        canvas.drawRect(rect, stroke);
        if (isSelected) {
          final handle = Paint()..color = palette[i % palette.length];
          for (final c in [rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight]) {
            canvas.drawCircle(c, 5, handle);
          }
        }
      } else {
        final path = Path();
        for (var k = 0; k < r.points.length; k += 2) {
          final p = Offset(r.points[k] * size.width, r.points[k + 1] * size.height);
          k == 0 ? path.moveTo(p.dx, p.dy) : path.lineTo(p.dx, p.dy);
        }
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawPath(path, stroke);
        if (isSelected) {
          final handle = Paint()..color = palette[i % palette.length];
          for (var k = 0; k < r.points.length; k += 2) {
            canvas.drawCircle(
                Offset(r.points[k] * size.width, r.points[k + 1] * size.height), 5, handle);
          }
        }
      }
    }
    final p = pendingPoly;
    if (p != null && p.length >= 2) {
      final stroke = Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5;
      final path = Path();
      for (var k = 0; k < p.length; k += 2) {
        final pt = Offset(p[k] * size.width, p[k + 1] * size.height);
        k == 0 ? path.moveTo(pt.dx, pt.dy) : path.lineTo(pt.dx, pt.dy);
      }
      canvas.drawPath(path, stroke);
    }
    final dr = dragRect;
    if (dr != null) {
      final stroke = Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5
        ..isAntiAlias = false;
      canvas.drawRect(
        Rect.fromLTRB(dr[0] * size.width, dr[1] * size.height,
            dr[2] * size.width, dr[3] * size.height),
        stroke,
      );
    }
  }

  @override
  bool shouldRepaint(_RegionPainter oldDelegate) =>
      oldDelegate.regions != regions ||
      oldDelegate.pendingPoly != pendingPoly ||
      oldDelegate.dragRect != dragRect ||
      oldDelegate.selected != selected;
}
```

> Note: the pan handlers above implement rectangle creation end-to-end: drag
> origin → live preview (`_dragRect`) → commit on `onPanEnd`. Tiny drags (< 0.02
> normalized) are ignored so stray taps don't create regions. Poly mode starts a
> `_pendingPoly` on the first tap and commits via the "Close poly" button.

> Note: `_commitPoly` requires at least 3 vertices (`p.length >= 6`). While a
> polygon is pending, tapping "Clear" abandons it; switching to Rectangle also
> abandons it (see `_onPanStart`).

- [ ] **Step 4: Wire the Settings entry**

Edit `security_cam/lib/ui/settings_screen.dart`:

1. Add imports:

```dart
import '../core/camera_session.dart';
import '../sensors/camera_source_factory.dart';
import 'region_editor_screen.dart';
```

(`AnalysisResolution` comes from `../core/settings.dart`, already imported. The
simulated/webcam/file sessions are constructed by `buildCameraSession`, so no
per-source imports are needed.)

2. Add a "Detection regions" card after the Detectors section (after line 194, before `Text('Channels', ...)`):

```dart
          const SizedBox(height: 24),
          Text('Detection regions', style: Theme.of(context).textTheme.titleMedium),
          const Text(
            'Optional inclusion zones: motion/face only triggers inside them. '
            'Empty = detect everywhere.',
            style: TextStyle(fontSize: 12),
          ),
          const SizedBox(height: 8),
          Card(
            child: ListTile(
              leading: const Icon(Icons.crop_free),
              title: Text(_draft.detectionRegions.isEmpty
                  ? 'No regions — detecting everywhere'
                  : '${_draft.detectionRegions.length} '
                      'region${_draft.detectionRegions.length == 1 ? '' : 's'}'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _openRegionEditor(),
            ),
          ),
```

3. Add the `_openRegionEditor` method (after `_save`):

```dart
  Future<void> _openRegionEditor() async {
    final camera = buildCameraSession(_draft);
    final (w, h) = AnalysisResolution.size(_draft.analysisResolution);
    await camera.init(CameraConfig(
      cameraId: camera.cameraId,
      analysisWidth: w,
      analysisHeight: h,
      analysisFps: 4,
    ));
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (context) => RegionEditorScreen(
          frames: camera.analysisFrames,
          initialRegions: _draft.detectionRegions,
          onSave: (regions) => setState(() {
            _draft = _draft.copyWith(detectionRegions: regions);
          }),
        ),
      ),
    );
    await camera.dispose();
  }
```

> Note: the preview camera runs only while the editor is open and is disposed
> on return. Desktop uses the simulated camera (immediate, no hardware); Android
> uses the native preview stream. If the configured `cameraSource` is
> `webcam`/`file` with no path set, `buildCameraSession` throws — that state is
> already surfaced on the Sources screen, so the editor only opens from a
> configured profile.

- [ ] **Step 5: Add the Settings entry test**

Append to `security_cam/test/settings_screen_test.dart`:

```dart
testWidgets('renders detection regions card with empty state', (tester) async {
  final controller = await _controller();
  addTearDown(controller.dispose);
  await _pump(tester, controller);

  await tester.scrollUntilVisible(
      find.text('Detection regions'), 300, scrollable: _listScrollable);
  expect(find.text('No regions — detecting everywhere'), findsOneWidget);
});
```

- [ ] **Step 6: Run tests**

Run: `date -R && cd security_cam && flutter test test/region_editor_screen_test.dart test/settings_screen_test.dart`
Expected: PASS.

> If the editor widget tests are flaky under `pumpAndSettle` (the CameraView
> stream never closes and keeps scheduling frames), replace `pumpAndSettle` with
> bounded `pump(const Duration(milliseconds: 100))` in the editor tests. The
> simulated camera emits on a periodic timer only while the session is alive; the
> injected `frames()` stream above is a broadcast controller with no timer, so it
> should settle fine.

- [ ] **Step 7: Commit**

```bash
git add security_cam/lib/ui/region_editor_screen.dart security_cam/lib/ui/settings_screen.dart security_cam/test/region_editor_screen_test.dart security_cam/test/settings_screen_test.dart
git commit -m "feat: inclusion-region editor with live preview + settings entry"
```

---

### Task 8: Monitor overlay (toggleable) on `CameraView`

**Files:**
- Modify: `security_cam/lib/ui/widgets/camera_view.dart`
- Modify: `security_cam/lib/ui/monitor_screen.dart`
- Modify: `security_cam/test/camera_view_test.dart`

- [ ] **Step 1: Write the failing widget tests**

Append inside the `group('CameraView', ...)` block in `security_cam/test/camera_view_test.dart` (existing imports already cover `models.dart` and `camera_view.dart`):

```dart
    Finder inCameraView() => find.descendant(
        of: find.byType(CameraView), matching: find.byType(CustomPaint));

testWidgets('renders region overlay when showRegions is true', (tester) async {
  final controller = StreamController<AnalysisFrame>.broadcast();
  addTearDown(controller.close);
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: CameraView(
        frames: controller.stream,
        regions: const [
          DetectionRegion(
              id: 'r1', shape: 'rect', label: 'doorway', points: [0.0, 0.0, 0.5, 0.5]),
        ],
        showRegions: true,
      ),
    ),
  ));
  controller.add(AnalysisFrame(
    timestamp: DateTime(2026),
    bitmap: GrayscaleBitmap(2, 2, Uint8List.fromList([140, 140, 140, 140])),
  ));
  await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 100)));
  await tester.pumpAndSettle();
  expect(inCameraView(), findsNWidgets(2),
      reason: 'frame paint + region overlay paint');
  expect(tester.takeException(), isNull);
});

testWidgets('no overlay paint when showRegions is false', (tester) async {
  final controller = StreamController<AnalysisFrame>.broadcast();
  addTearDown(controller.close);
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: CameraView(
        frames: controller.stream,
        regions: const [
          DetectionRegion(
              id: 'r1', shape: 'rect', label: 'doorway', points: [0.0, 0.0, 0.5, 0.5]),
        ],
        showRegions: false,
      ),
    ),
  ));
  controller.add(AnalysisFrame(
    timestamp: DateTime(2026),
    bitmap: GrayscaleBitmap(2, 2, Uint8List.fromList([140, 140, 140, 140])),
  ));
  await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 100)));
  await tester.pumpAndSettle();
  expect(inCameraView(), findsOneWidget,
      reason: 'only the frame paint when overlay is off');
  expect(tester.takeException(), isNull);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && cd security_cam && flutter test test/camera_view_test.dart`
Expected: FAIL — `regions`/`showRegions` are not parameters of `CameraView`.

- [ ] **Step 3: Implement the overlay**

Edit `security_cam/lib/ui/widgets/camera_view.dart`:

1. Add fields to `CameraView`:

```dart
  final List<DetectionRegion> regions;
  final bool showRegions;
```

2. Constructor params:

```dart
  const CameraView({
    super.key,
    required this.frames,
    this.initialFrame,
    this.decoder = decodeFrame,
    this.regions = const [],
    this.showRegions = false,
  });
```

3. In `build`, wrap the `CustomPaint` in a `Stack` (replace lines 119–122):

```dart
            : Stack(
                fit: StackFit.expand,
                children: [
                  CustomPaint(
                    size: Size.infinite,
                    painter: _FramePainter(image),
                  ),
                  if (showRegions && regions.isNotEmpty)
                    CustomPaint(
                      size: Size.infinite,
                      painter: _RegionOverlayPainter(
                          regions, image.width.toDouble(), image.height.toDouble()),
                    ),
                ],
              ),
```

4. Add the painter class at the end of the file:

```dart
/// Draws the inclusion regions over the decoded frame. Regions are normalized
/// 0..1 relative to the analysis frame; the overlay maps them onto the same
/// 4:3 aspect the frame uses, so the mapping is direct.
class _RegionOverlayPainter extends CustomPainter {
  final List<DetectionRegion> regions;
  final double frameWidth;
  final double frameHeight;

  _RegionOverlayPainter(this.regions, this.frameWidth, this.frameHeight);

  static const _palette = [
    Color(0xCC8AB4F8),
    Color(0xCC81C995),
    Color(0xCCFDD663),
    Color(0xCCF28B82),
    Color(0xCCD7AEFB),
  ];

  @override
  void paint(Canvas canvas, Size size) {
    final scaleX = size.width / frameWidth;
    final scaleY = size.height / frameHeight;
    for (var i = 0; i < regions.length; i++) {
      final r = regions[i];
      final stroke = Paint()
        ..color = _palette[i % _palette.length]
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5
        ..isAntiAlias = false;
      if (r.shape == DetectionRegionShape.rect) {
        canvas.drawRect(
          Rect.fromLTRB(
            r.points[0] * scaleX,
            r.points[1] * scaleY,
            r.points[2] * scaleX,
            r.points[3] * scaleY,
          ),
          stroke,
        );
      } else {
        final path = Path();
        for (var k = 0; k < r.points.length; k += 2) {
          final p = Offset(r.points[k] * scaleX, r.points[k + 1] * scaleY);
          k == 0 ? path.moveTo(p.dx, p.dy) : path.lineTo(p.dx, p.dy);
        }
        path.close();
        canvas.drawPath(path, stroke);
      }
    }
  }

  @override
  bool shouldRepaint(_RegionOverlayPainter oldDelegate) =>
      oldDelegate.regions != regions ||
      oldDelegate.frameWidth != frameWidth ||
      oldDelegate.frameHeight != frameHeight;
}
```

- [ ] **Step 4: Add the Monitor toggle**

Edit `security_cam/lib/ui/monitor_screen.dart` — convert `MonitorScreen` to a
`StatefulWidget` with a `_showRegions` bool and pass the overlay to `CameraView`:

```dart
class MonitorScreen extends StatefulWidget {
  final MonitorController controller;

  const MonitorScreen({super.key, required this.controller});

  @override
  State<MonitorScreen> createState() => _MonitorScreenState();
}

class _MonitorScreenState extends State<MonitorScreen> {
  bool _showRegions = false;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.controller,
      builder: (context, _) {
        final controller = widget.controller;
        final monitoring = controller.state == MonitorState.monitoring;
        return SafeArea(
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.videocam_outlined),
                title: Text(controller.settings.cameraName),
                subtitle: Text(switch (controller.state) {
                  MonitorState.idle => 'Idle',
                  MonitorState.starting => 'Starting…',
                  MonitorState.monitoring => 'Monitoring',
                  MonitorState.error => 'Error',
                }),
                trailing: monitoring
                    ? const Icon(Icons.circle, color: Colors.red, size: 12)
                    : null,
              ),
              Expanded(
                child: Center(
                  child: controller.analysisFrames == null
                      ? const Text('Start monitoring to view the camera')
                      : Padding(
                          padding: const EdgeInsets.all(8),
                          child: CameraView(
                            frames: controller.analysisFrames!,
                            regions: controller.settings.detectionRegions,
                            showRegions: _showRegions,
                          ),
                        ),
                ),
              ),
              if (controller.state == MonitorState.error)
                Padding(
                  padding: const EdgeInsets.all(8),
                  child: Text(
                    'Error: ${controller.error}',
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: FilledButton.icon(
                            onPressed: monitoring
                                ? () => controller.stop()
                                : () => controller.start(),
                            icon: Icon(monitoring ? Icons.stop : Icons.play_arrow),
                            label: Text(monitoring ? 'Stop' : 'Start'),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: InputDecorator(
                            decoration: const InputDecoration(
                              labelText: 'Audio scene',
                              border: OutlineInputBorder(),
                            ),
                            child: DropdownButtonHideUnderline(
                              child: DropdownButton<AudioScene>(
                                value: controller.audioScene,
                                isDense: true,
                                items: AudioScene.values
                                    .map((s) => DropdownMenuItem(
                                          value: s,
                                          child: Text(_sceneLabel(s)),
                                        ))
                                    .toList(),
                                onChanged: (scene) {
                                  if (scene != null) {
                                    controller.setAudioScene(scene);
                                  }
                                },
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                    if (monitoring)
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Show regions'),
                        subtitle: const Text(
                          'Display the inclusion zones on the live feed.',
                          style: TextStyle(fontSize: 12),
                        ),
                        value: _showRegions,
                        onChanged: (v) => setState(() => _showRegions = v),
                      ),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  String _sceneLabel(AudioScene scene) {
    return switch (scene) {
      AudioScene.babyCry => 'Baby crying',
      AudioScene.glassBreak => 'Glass breaking',
      AudioScene.bang => 'Loud noise',
      AudioScene.silence => 'Silence',
    };
  }
}
```

- [ ] **Step 5: Run tests**

Run: `date -R && cd security_cam && flutter test test/camera_view_test.dart`
Expected: PASS.

- [ ] **Step 6: Run the full desktop unit suite**

Run: `date -R && cd security_cam && flutter test`
Expected: PASS (all existing tests unaffected; `MonitorScreen` API unchanged).

- [ ] **Step 7: Commit**

```bash
git add security_cam/lib/ui/widgets/camera_view.dart security_cam/lib/ui/monitor_screen.dart security_cam/test/camera_view_test.dart
git commit -m "feat: toggleable inclusion-region overlay on the monitor feed"
```

---

### Task 9: Desktop smoke + full suite

**Files:**
- Run-time only (no source changes unless a bug appears)

- [ ] **Step 1: Run the full unit suite**

Run: `date -R && cd security_cam && flutter test`
Expected: PASS.

- [ ] **Step 2: Manual desktop smoke**

Run: `date -R && cd security_cam && flutter run -d linux`
1. Start monitoring (simulated camera).
2. Settings → Detection regions → draw a rectangle in the top-left; Done; Save settings.
3. In Settings, confirm the card now shows "1 region".
4. On Monitor, enable "Show regions" — the rectangle renders over the feed.
5. (Optional) with a region drawn, the simulated moving object should only
   trigger motion when it moves inside the region — verify via the event log.

Expected: regions persist across restart (Settings reload), overlay toggles, and
motion triggers are region-restricted.

- [ ] **Step 3: Commit any smoke fixes**

If the smoke run surfaced bugs, fix them with a test and commit:

```bash
git add -A security_cam
git commit -m "fix: region smoke fixes"
```

(If nothing to fix, skip this step — do not create an empty commit.)