# Privacy (Exclusion) Zones — Native Port Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [ ]`)
> syntax for tracking. One commit per task; unit suite green before every commit.

**Goal:** Port the shipped Flutter design to the native Kotlin app: mask detection
(motion, face, person) inside user-defined exclusion zones, edited in the same
region editor via an inclusion/exclusion mode toggle and visible in the live overlay.

**Spec:** `docs/plans/2026-08-19-privacy-zones-design.md` (Dart-era; semantics
unchanged). The Dart implementation was lost in the Phase 7 cutover — this port
recreates it natively.

**Architecture:** A second persisted list `AppSettings.exclusionRegions` reuses the
`DetectionRegion` model + `RegionFilter` geometry. Motion uses
`RegionFilter.pixelMaskExcluding(inclusions, exclusions, w, h)`; face/person use
`rectOverlapsAny(inclusions) && !rectOverlapsAny(exclusions)`. Editor and overlay
render both lists (exclusions in red).

**Execution rule:** JVM unit tests first (Robolectric where Compose is touched);
staging instrumentation pass at the end.

---

### Task 1: Model + geometry + pipeline plumbing

- [x] **Step 1:** `core/Settings.kt` — `AppSettings.exclusionRegions`
  (default `[]`), `copyWith`, `toJson`, `fromJson` (missing key → empty).
- [x] **Step 2:** `detection/RegionFilter.kt` — `pixelMaskExcluding(inclusions,
  exclusions, width, height)`: inclusion mask (or all-ones when no inclusions),
  then clear pixels whose center lies inside any exclusion.
- [x] **Step 3:** `detection/Detector.kt` — `FrameDetector.exclusionRegions`.
- [x] **Step 4:** `detection/pipeline/DetectorPipeline.kt` — `setRegions(inclusions,
  exclusions)` fans both out.
- [x] **Step 5:** `monitor/MonitoringRuntime.kt` passes `settings.exclusionRegions`.
- [x] **Step 6:** Tests — Settings round-trip incl. legacy JSON without the key;
  `RegionFilterTest`: mask cleared inside exclusion, identical to `pixelMask`
  when exclusions empty, full-frame exclusion → zero pixels.
- [x] **Step 7:** Verify + commit.

### Task 2: Motion + face/person honor exclusion zones

- [x] **Step 1:** `MotionDetector` builds its cached mask via
  `pixelMaskExcluding`; cache invalidates when either list identity changes;
  `reset()` clears both refs.
- [x] **Step 2:** `FaceDetector`/person engine keep a detection iff it overlaps an
  inclusion zone (or there are none) AND does not overlap any exclusion.
- [x] **Step 3:** Tests — motion: change inside an exclusion does not trigger with
  no inclusions; face/person: dropped inside, kept outside, inclusion+exclusion
  interaction.
- [x] **Step 4:** Verify + commit.

### Task 3: Editor mode toggle + settings wiring

- [x] **Step 1:** `RegionEditorViewModel` holds both lists; tools operate on the
  active mode only.
- [x] **Step 2:** `RegionEditorScreen` gains an Inclusion/Exclusion SegmentedButton;
  each mode lists/draws its own zones (exclusions red); Done →
  `onSave(inclusions, exclusions)`.
- [x] **Step 3:** Call site (`SecurityCamApp`) passes draft's both lists and saves
  both back via settings view-model update.
- [x] **Step 4:** Tests — mode switch isolates lists; tools hit active list; round-trip.
- [x] **Step 5:** Verify + commit.

### Task 4: Overlay shows exclusion zones

- [x] **Step 1:** `MonitorViewModel.exclusionRegions` StateFlow alongside
  `detectionRegions`; fed from loaded settings.
- [x] **Step 2:** `ui/monitor/RegionOverlay.kt` draws exclusions in a distinct red;
  regions toggle reveals both.
- [x] **Step 3:** Test — view-model picks up exclusions from settings load.
- [x] **Step 4:** Verify + commit.

### Final verification

- [x] Full unit suite (296/296) + `assembleDebug`.
- [x] Staging instrumentation pass (`OK (18 tests)`, 146 s) on `pixel_34_aosp` (`run_android_integration_tests.sh <serial> all`),
      emulator killed afterwards.
- [x] Tick this plan; add parity-matrix rows for the new native tests.
