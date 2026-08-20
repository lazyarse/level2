# Privacy (Exclusion) Zones — Implementation Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [ ]`)
> syntax for tracking. This plan is part of the multi-workstream feature batch
> (2026-08-19); execute after go-ahead.

**Goal:** Mask detection (motion, face, person) inside user-defined privacy zones,
edited in the same region editor via an inclusion/exclusion mode toggle and
visible in the live overlay.

**Architecture:** A second persisted list `AppSettings.exclusionRegions` reuses the
`DetectionRegion` model + geometry. Motion uses a combined
`pixelMaskExcluding(inclusions, exclusions)`; face/person use
`rectOverlapsAny(inclusions) && !rectOverlapsAny(exclusions)`. The editor and
overlay draw both lists.

**Spec:** `docs/plans/2026-08-19-privacy-zones-design.md`

**Execution rule:** Prefer Linux desktop (`flutter test`) for all iteration; the
change is pure Dart.

---

### Task 1: Model + geometry + pipeline plumbing

**Files:**
- Modify: `security_cam/lib/core/settings.dart`
- Modify: `security_cam/lib/detection/regions/region_filter.dart`
- Modify: `security_cam/lib/core/detector.dart`
- Modify: `security_cam/lib/detection/pipeline.dart`
- Modify: `security_cam/lib/state/monitor_controller.dart`

- [ ] **Step 1:** Add `AppSettings.exclusionRegions` (default `[]`) with `copyWith`,
  `toJson`, `fromJson` (`lib/core/settings.dart`).
- [ ] **Step 2:** Add `pixelMaskExcluding(List<DetectionRegion> inclusions,
  List<DetectionRegion> exclusions, int width, int height)` to
  `region_filter.dart` (build inclusion mask — or all-true when inclusions empty —
  then clear pixels whose centers are inside any exclusion; reuse `pointInRegion`).
- [ ] **Step 3:** Add `exclusionRegions` field to `FrameDetector` (default `const []`).
- [ ] **Step 4:** Extend `DetectorPipeline.setRegions` → `setRegions(inclusions,
  exclusions)` fanning to both `regions` and `exclusionRegions` on every frame
  detector.
- [ ] **Step 5:** `monitor_controller.dart` passes
  `pipeline.setRegions(settings.detectionRegions, settings.exclusionRegions)`.
- [ ] **Step 6:** Tests: `pixelMaskExcluding` (mask cleared inside exclusion,
  empty-exclusion == legacy, full-frame exclusion → empty mask); settings JSON
  round-trip; pipeline fan-out test updated.
- [ ] **Step 7:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: exclusion zones model, mask geometry, and pipeline plumbing"
  ```

### Task 2: Motion + face/person honor exclusion zones

**Files:**
- Modify: `security_cam/lib/detection/motion_detector.dart`
- Modify: `security_cam/lib/detection/face/face_detector.dart`
- Modify: `security_cam/lib/detection/person_detector.dart`
- Modify: `security_cam/test/motion_detector_test.dart`
- Modify: `security_cam/test/face_detector_test.dart`
- Modify: `security_cam/test/person_detector_test.dart`

- [ ] **Step 1:** `MotionDetector` builds the mask via `pixelMaskExcluding` and
  extends the cache-invalidation identity check to cover **both** region lists
  (inclusions and exclusions); `reset()` clears the cached mask.
- [ ] **Step 2:** `FaceDetector`/`PersonDetector` filter:
  `rectOverlapsAny(regions) && !rectOverlapsAny(exclusionRegions)` (inclusion
  check passes when `regions` empty).
- [ ] **Step 3:** Tests — motion: change inside an exclusion does not trigger (no
  inclusions); inclusion+exclusion interaction; exclusion-only motion is ignored.
  Face/person: box inside exclusion dropped, outside kept, empty-region behavior
  unchanged.
- [ ] **Step 4:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: motion, face, and person detectors honor exclusion zones"
  ```

### Task 3: Editor mode toggle + settings wiring

**Files:**
- Modify: `security_cam/lib/ui/region_editor_screen.dart`
- Modify: `security_cam/lib/ui/settings_screen.dart`
- Modify: `security_cam/test/region_editor_screen_test.dart`
- Modify: `security_cam/test/settings_screen_test.dart`

- [ ] **Step 1:** `RegionEditorScreen` — add an Inclusion/Exclusion mode toggle;
  `initialInclusions`/`initialExclusions` + `onSave(inclusions, exclusions)`;
  tools/state per active mode; lists render both modes with mode-specific colors
  (exclusion = red).
- [ ] **Step 2:** Settings "Detection regions" card (`settings_screen.dart`) — add
  an exclusion count/entry; `_openRegionEditor` passes and saves both lists
  (update `_draft` via `copyWith`).
- [ ] **Step 3:** Tests — mode toggle switches drawn/list content; Done returns
  both lists; settings screen shows exclusion entry and round-trips; keep
  existing region-editor tests passing (update for the new signature).
- [ ] **Step 4:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: inclusion/exclusion mode toggle in region editor + settings"
  ```

### Task 4: Overlay shows exclusion zones

**Files:**
- Modify: `security_cam/lib/ui/widgets/camera_view.dart`
- Modify: `security_cam/lib/ui/monitor_screen.dart`
- Modify: `security_cam/test/camera_view_test.dart`
- Modify: `security_cam/test/monitor_screen_test.dart`

- [ ] **Step 1:** `CameraView` gains `exclusionRegions`; `_RegionOverlayPainter`
  draws exclusions in red (distinct from inclusion palette) and inclusions as today.
- [ ] **Step 2:** `MonitorScreen` passes `controller.settings.exclusionRegions` to
  `CameraView` alongside `detectionRegions`.
- [ ] **Step 3:** Tests — overlay paints both kinds when shown (counts of painted
  shapes / CustomPaint presence), nothing extra when hidden.
- [ ] **Step 4:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: exclusion zones visible in monitor overlay"
  ```

---

## Self-Review notes

- **Spec coverage:** separate persisted list ✓; editor mode toggle ✓; overlay ✓;
  motion/face/person masking ✓; pipeline plumbing ✓.
- **Key decision:** exclusion wins over inclusion everywhere (a masked pixel is
  never detected even if inside an inclusion zone).
- **Blast radius:** regions subsystem only — detector algorithms, native stack,
  channels, and events are untouched.
- **Cache correctness:** motion mask invalidation must key on the **pair** of
  lists (identity), not just inclusions — explicit in Task 2 Step 1.