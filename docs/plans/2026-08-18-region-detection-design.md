# Detection Regions (Inclusion Zones) — Design

Date: 2026-08-18
Status: Approved (Aug 18 2026) — design reviewed via brainstorming (sections 1–4); implementation plan pending

## Goal

Add optional **inclusion regions** (ROI) to frame detection: a shared set of
user-drawn regions on the analysis frame; all frame detectors (motion now, face
and person later) only trigger when activity is *inside* a region. With zero
regions defined, behavior is identical to today (whole frame). Audio detectors
are unaffected (no spatial location).

## Locked decisions (from Q&A)

| Topic | Decision |
|---|---|
| Core intent | **Inclusion zones (ROI)** — only alert when activity is inside marked regions |
| Scope | **Global** — one region set gates all frame detectors (motion, face, person) |
| Shape | **Rectangles + polygons**; the user picks the shape type per region in the editor UI |
| Editor | **Settings → "Detection regions"** full-screen editor with a live analysis-frame preview; Done saves into the settings draft |
| Box rule | **Any overlap** — a box-based detection (face/person) counts if its box intersects any region |
| Empty default | **No regions = whole frame** (backward compatible) |
| Monitor overlay | **Toggleable** translucent region outlines on the live Monitor feed, off by default |
| Coords | **Normalized 0..1 on the analysis frame** — scales automatically across resolution presets and orientation |
| Architecture | **Region-aware detectors** — the pipeline holds the region set and fans it out to `FrameDetector`s; each spatial detector applies its own rule |

## Data model — `DetectionRegion`

New class in `lib/core/models.dart`:

```dart
class DetectionRegion {
  final String id;            // stable, for editing/delete targeting
  final String shape;         // 'rect' | 'poly'
  final String label;         // user-friendly name shown in the editor list
                              // (e.g. 'doorway', 'driveway')
  final List<double> points;  // normalized 0..1, flattened [x0,y0,x1,y1] for
                              // rect; [x0,y0,x1,y1,...] vertex pairs for poly
  const DetectionRegion({
    required this.id,
    required this.shape,
    required this.label,
    required this.points,
  });
  // toJson / fromJson following the DetectorConfig pattern
}
```

- Shape is a string (mirrors `CameraSource`/`AudioInput`/`VideoQuality`
  pseudo-enum style in `lib/core/settings.dart`); `values = ['rect', 'poly']`.
- Coordinates are **normalized 0..1 relative to the analysis frame** (not the
  full-res camera image), so regions survive analysis-resolution changes and
  aspect-ratio changes without redrawing.
- `AppSettings` gains `List<DetectionRegion> detectionRegions` (default
  `const []`), with `copyWith`, `toJson`/`fromJson` round-trip following the
  existing `AppSettings` pattern.

## Geometry — `RegionFilter`

New pure-Dart module `lib/detection/regions/region_filter.dart`:

- `bool pointInRegion(DetectionRegion r, double x, double y)` — ray-casting for
  `poly`, simple inclusive-bounds test for `rect`. Coordinates normalized 0..1.
- `bool rectOverlapsAny(List<DetectionRegion> regions, double x, double y, double w, double h)` —
  the **any-overlap** rule for box detectors. `regions` empty → `true` (whole-frame fallback).
  Uses `pointInRegion` on the box corners for containment, plus edge-intersection
  for the cross-boundary case.
- `Uint8List pixelMask(List<DetectionRegion> regions, int width, int height)` →
  a byte mask (1 = inside *any* region, union of overlapping regions) and
  `int pixelCount` — the number of 1-bits. Used by the motion detector for its
  restricted diff; rebuilt once per region-set/frame-dimension change.

## Pipeline & detector integration (Approach A — region-aware detectors)

**Wiring** — the region set lives on the pipeline, not the frame or the camera.
Detectors are built inside `DetectorPipeline`'s constructor initializer list
(from `detectorRegistry`), so regions cannot be a constructor arg that reaches
them — the fan-out happens after construction:

- `FrameDetector` (abstract, `lib/core/detector.dart`) gains an optional mutable
  `List<DetectionRegion> regions` field (default `const []`). Detectors that
  don't care ignore it.
- `DetectorPipeline` gains `setRegions(List<DetectionRegion> regions)` that
  stores the set and fans it out to each `_frameDetectors` entry.
- `MonitorController.start()` calls `pipeline.setRegions(...)` with
  `settings.detectionRegions` right after `pipeline.init()`, so regions are fixed
  for the lifetime of a monitoring run (settings changes require restart,
  consistent with other pipeline inputs).

**MotionDetector** (`lib/detection/motion_detector.dart`):

- Caches a `pixelMask` at the current frame dimensions; rebuilt when `regions`
  or frame size changes.
- `_diffRatio` counts changed pixels **only where mask == 1**; denominator =
  mask `pixelCount` (union area), not `width * height`.
- Empty regions → mask is all-ones, denominator = total pixels → exactly today's
  behavior and numbers.
- Net effect: a small doorway region + moderate change triggers at the same
  ratio as a full-frame event today, because the threshold is relative to region
  area, not frame area.

**FaceDetector** (lands in the face plan's Task 6; this plan provides the hook):

- After the engine returns faces, filter with `rectOverlapsAny(regions, faceBox)`
  before persistence/threshold logic. Boxes not overlapping any region are
  dropped. Empty regions → all pass (no change).
- Composition with motion gating (face plan Task 4) is natural: motion *inside a
  region* gates face inference; face boxes must then *overlap a region*.

**Person detection** (Phase 2, future): same box-filter hook — no design change.

**Audio detectors** (baby cry, glass break, loud noise): untouched.

## Settings editor UI

**Settings entry** — a "Detection regions" card after the **Detectors** section:
shows `"No regions — detecting everywhere"` or `"N regions"`, opens the editor.
The live camera runs only while the editor is open.

**Editor screen** (full-screen route pushed over Settings):

- Top bar: title "Detection regions" + **Done** (returns to Settings).
- Live analysis-frame preview — a preview-only `CameraSession`/`CameraView` at
  the configured analysis resolution.
- **Tool bar** below the preview: shape picker (**Rectangle | Polygon**),
  **+ Add**, **Clear all** (with confirm).
  - *Rectangle*: drag on the preview to place; drag corners to resize; drag body
    to move; tap to select (edit label + delete).
  - *Polygon*: tap to place vertices; close back to the first vertex (or Done) to
    finish; drag vertices to adjust; tap to select/delete.
- **Region list**: color-coded entries (`■ doorway · rect`, `■ driveway · poly`)
  each with a delete ✕. New regions get a default label ("Region 1", "Region 2",
  …) that the user can edit on selection.
- Done writes the working region set into the Settings draft (saved only via the
  existing **Save settings** flow); backing out without Done discards.

## Monitor overlay

- `CameraView` gains an optional region overlay painter: dashed translucent
  outlines, per-region color, drawn over the decoded frame at normalized coords.
- `MonitorScreen` gains a small **"Show regions"** switch (visible while
  monitoring), default off, toggling the overlay.

## Testing

All geometry is pure Dart → Linux desktop unit tests, no emulator:

| Test file | Coverage |
|---|---|
| `test/region_filter_test.dart` (new) | point-in-rect; point-in-poly (convex + concave); any-overlap (contained, corner-touch, cross-boundary, no-overlap); empty regions → whole frame; `pixelMask` union of overlapping regions + `pixelCount` |
| `test/motion_detector_test.dart` (extend) | change inside region triggers; identical change outside region does not; small region + moderate change triggers at the same ratio as a full-frame event; empty regions = legacy behavior |
| `test/settings_test.dart` (extend) | `detectionRegions` JSON round-trip (rect + poly), default `const []`, `copyWith` |
| `test/settings_screen_test.dart` / `test/camera_view_test.dart` (extend) | region card shows count; editor Done saves regions into the draft; `CameraView` overlay + Monitor "Show regions" toggle show/hide regions |
| Face hook | unit test for `rectOverlapsAny` only — real `FaceDetector` integration lands with the face plan |

Desktop smoke: simulated camera with a drawn region — motion in-region triggers,
outside doesn't. No Android emulator needed: regions are applied in Dart
post-analysis-frame; the native camera path is untouched.

## Deferred / not in scope

- Per-detector region sets (global only this phase).
- Exclusion/privacy-mask semantics (inclusion ROI only this phase).
- Region-aware snapshot cropping (snapshots remain full frame).
- Polygon support in the box-overlap path is handled by the same
  `rectOverlapsAny` geometry — no extra work later.