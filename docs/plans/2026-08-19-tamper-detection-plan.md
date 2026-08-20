# Tamper Detection (Camera Covered / Moved) — Implementation Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [ ]`)
> syntax for tracking. This plan is part of the multi-workstream feature batch
> (2026-08-19); execute after go-ahead.

**Goal:** A `tamper` trigger type with a `detail` ('covered'/'moved') propagated to
alert text ("Camera covered" / "Camera moved"), implemented as a self-contained
luminance + coarse-grid heuristic `FrameDetector`, off by default.

**Architecture:** `TamperDetector` works off `AnalysisFrame.bitmap` (grayscale),
learns a baseline (mean/σ + 8×8 cell means) over a warm-up period, triggers
"covered" on sustained dark frames and "moved" on sustained cell-signature change
with low inter-frame motion. `detail` flows `DetectionResult → TriggerEvent →
alert text`.

**Spec:** `docs/plans/2026-08-19-tamper-detection-design.md`

**Execution rule:** Prefer Linux desktop (`flutter test`) for all iteration; pure Dart.

---

### Task 1: `detail` plumbing + `tamper` trigger type

**Files:**
- Modify: `security_cam/lib/core/models.dart`
- Modify: `security_cam/lib/detection/pipeline.dart`
- Modify: `security_cam/lib/event/event_pipeline.dart`
- Modify: `security_cam/lib/ui/settings_screen.dart`
- Modify: `security_cam/lib/ui/events_screen.dart`

- [ ] **Step 1:** Add `TriggerType.tamper = 'tamper'`; add `final String? detail`
  to `DetectionResult` and `TriggerEvent`; `pipeline._maybeEmit` copies
  `result.detail` into the emitted `TriggerEvent`.
- [ ] **Step 2:** `_alertText` (`event_pipeline.dart:117-123`): when a batch has a
  single trigger with a non-null `detail`, use it (`covered` → "Camera covered",
  `moved` → "Camera moved") instead of the generic label; add `tamper` to
  `triggerLabel` ("Tamper").
- [ ] **Step 3:** Add `tamper` to settings `_label` and events `_iconFor` via the
  shared label map (from the dog-audio workstream).
- [ ] **Step 4:** Tests — `DetectionResult`/`TriggerEvent` carry `detail` through
  `_maybeEmit`; alert-text test for covered/moved single-trigger batches;
  label/icon map coverage for `tamper`.
- [ ] **Step 5:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: tamper trigger type + detection detail in alerts"
  ```

### Task 2: `TamperDetector` + registry + defaults

**Files:**
- Create: `security_cam/lib/detection/tamper_detector.dart`
- Create: `security_cam/test/tamper_detector_test.dart`
- Modify: `security_cam/lib/core/registries.dart`
- Modify: `security_cam/lib/core/settings.dart`

- [ ] **Step 1:** `TamperDetector(this.config, {int warmUpFrames = 60, double
  cellDelta = 0.08, double motionFloor = 0.01})`:
  - warm-up: accumulate mean luma → μ/σ and 8×8 cell means (pure averaging of
    `bitmap.gray`); not armed until `warmUpFrames` frames seen.
  - covered: mean luma `< max(0.03, μ − 3σ)` sustained `persistenceFrames` →
    `DetectionResult(triggered: true, detail: 'covered', score: clamped darkness)`.
  - moved: cellChange (fraction of cells differing by > `cellDelta` from baseline)
    ≥ `config.threshold` sustained `persistenceFrames` **and** frame-to-frame mean
    abs diff < `motionFloor` → `detail: 'moved'`, `score: cellChange`.
  - `reset()` clears baseline/persistence; dispose no-op.
- [ ] **Step 2:** Registry entry (`lib/core/registries.dart`) +
  `AppSettings.defaults()` disabled config (threshold 0.5, persistenceFrames 3,
  cooldown 120 s, motionGated false).
- [ ] **Step 3:** Unit tests — warm-up not armed; covered on sustained dark frames;
  moved on sustained cell-change with low inter-frame motion; moved suppressed
  when inter-frame motion high; reset re-arms; score shapes.
- [ ] **Step 4:** Pipeline test — tamper detector emits `TriggerEvent` with
  `detail`, cooldown respected.
- [ ] **Step 5:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: tamper detector (covered/moved) with baseline heuristic"
  ```

---

## Self-Review notes

- **Spec coverage:** `detail` plumbing ✓; tamper type ✓; heuristic detector ✓;
  alert text ✓; registry/defaults ✓; tests ✓.
- **Key decision:** self-contained inter-frame motion discriminator (no coupling
  to MotionDetector) — documented limitation is a slow pan reading as "moved".
- **Blast radius:** additive trigger plumbing + one new detector; pipeline,
  channels, storage untouched.
- **Deferred:** correlation with MotionDetector state; image-signature matching;
  offline-tamper (health watchdog W7 covers device-level stalls).