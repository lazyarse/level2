# Privacy (Exclusion) Zones — Design

Date: 2026-08-19
Status: Draft (implementation plan follows)

## Goal

Add **privacy/exclusion zones**: regions of the frame where detection is
**masked entirely** — motion diff, face, and person detectors all ignore content
inside them. Exclusion zones reuse the existing inclusion-region geometry and are
edited in the **same editor via a mode toggle**; they stay visible in the live
preview overlay (distinct color) so the user can see what is masked.

## Current state (verified from code, 2026-08-19)

- **Inclusion regions exist end-to-end.** `DetectionRegion{id, shape, label,
  points}` (`lib/core/models.dart`), persisted as `AppSettings.detectionRegions`
  (default `[]`), geometry in `lib/detection/regions/region_filter.dart`
  (`pointInRegion`, `rectOverlapsAny`, `pixelMask`), editor
  `lib/ui/region_editor_screen.dart`, settings entry "Detection regions" card in
  `lib/ui/settings_screen.dart`, overlay in `lib/ui/widgets/camera_view.dart`
  (`_RegionOverlayPainter`, toggleable from `MonitorScreen`).
- **Motion** uses a cached pixel mask: `MotionDetector` builds `pixelMask(regions,
  w, h)` once, invalidates on region-list identity or frame-size change, and
  computes the diff ratio only inside the mask (`lib/detection/motion_detector.dart`).
- **Face/Person** filter detected boxes with `rectOverlapsAny(regions, ...)`
  (normalized to the color frame) — `lib/detection/face/face_detector.dart`,
  `lib/detection/person_detector.dart`; both read `FrameDetector.regions`.
- **Pipeline** fans `regions` out via `DetectorPipeline.setRegions(regions)` →
  each `FrameDetector.regions` (`lib/detection/pipeline.dart`,
  `lib/state/monitor_controller.dart` calls
  `pipeline.setRegions(settings.detectionRegions)`).
- **Editor** signature: `RegionEditorScreen({required frames, required
  initialRegions, required onSave(List<DetectionRegion>)})`.

## Design

### 1. Data model: a separate list, reusing the region model

- New `AppSettings.exclusionRegions: List<DetectionRegion>` (default `[]`), full
  JSON round-trip + `copyWith`. Reuses the existing `DetectionRegion` model and
  geometry — no new shape types. Rationale: inclusion and exclusion are
  semantically distinct policies with distinct editor modes and overlay colors;
  separate lists keep persistence and editor state clean while sharing the model,
  geometry functions, and painter.

### 2. Editor: mode toggle

- `RegionEditorScreen` gains an **Inclusion / Exclusion mode** toggle (SegmentedButton
  or AppBar action). It edits and lists both lists: each mode shows its own zones
  in its own color (inclusion = existing palette, exclusion = red). Tools
  (rect/poly/add/clear/label) operate on the active mode. Done saves both:
  `onSave(List<DetectionRegion> inclusions, List<DetectionRegion> exclusions)`.
- Settings screen `_openRegionEditor` passes both lists and saves both back to the
  draft.

### 3. Overlay

- `CameraView` gains `exclusionRegions`; `_RegionOverlayPainter` draws exclusion
  zones in a distinct color (e.g. red, possibly hatched) alongside inclusions.
  The monitor overlay toggle shows both.

### 4. Detection semantics (exclusion wins)

- **Motion:** the cached pixel mask becomes `pixelMaskExcluding(inclusions,
  exclusions, w, h)` — start from the inclusion mask (or full frame if no
  inclusions), then clear every pixel whose center lies inside any exclusion zone.
  `MotionDetector` tracks both lists for cache invalidation; `reset()` clears.
- **Face / Person:** keep a detection iff it overlaps an inclusion zone (or there
  are none) **and** does **not** overlap any exclusion zone:
  `rectOverlapsAny(inclusions) && !rectOverlapsAny(exclusions)`.
- **Pipeline:** `setRegions` becomes `setRegions(inclusions, exclusions)`;
  `FrameDetector` gains `exclusionRegions` alongside `regions`.

## Verification

- **Geometry tests** (`test/region_filter_test.dart`): `pixelMaskExcluding`
  clears masked pixels; inclusion-only behavior identical to legacy when
  exclusions empty; full-frame exclusion yields an empty mask.
- **Detector tests**: motion — change inside an exclusion does not trigger when
  inclusion empty; face/person — detection inside exclusion is dropped, outside
  kept, inclusion+exclusion interaction.
- **Editor/UI tests**: mode toggle switches drawn/list content; both lists saved
  on Done; settings round-trip of `exclusionRegions`; overlay draws exclusion
  zones.
- **Existing suite**: `flutter test` + `flutter analyze` green on Linux desktop.

## Deferred / not in this phase

- Per-zone enable toggle (a disabled zone kept for later use).
- Exclusion from snapshot/alert delivery itself (zones mask detection, so no
  trigger fires — but a future manual snapshot button would still capture the
  masked area).
- Exclusion zones for audio or for the scheduled-recording policy.

## Risks

- Low. Pure extension of an already-shipped subsystem (regions). Main risks:
  mask cache invalidation now depends on **two** lists (identity tracking must
  cover both), and the editor grows a second list with its own tool state — both
  covered by tests.