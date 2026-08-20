# Tamper Detection (Camera Covered / Moved) — Design

Date: 2026-08-19
Status: Draft (implementation plan follows)

## Goal

Detect physical tampering — the camera being **covered** (lens blocked / dark
frame) or **moved** (scene change with no expected motion) — and raise it as a
first-class routable trigger type (`tamper`) with a `detail` distinguishing the
two cases, so the alert reads "Camera covered" vs "Camera moved".

Decision (2026-08-19): v1 uses luminance + motion baselines — no new model.

## Current state (verified from code, 2026-08-19)

- **Frame pipeline**: `DetectorPipeline.processFrame` runs frame detectors
  synchronously then awaits motion-gated async detectors (`lib/detection/pipeline.dart`).
  `FrameDetector` (`lib/core/detector.dart:79-91`) exposes `regions` + a sync
  `analyzeFrame(AnalysisFrame)` (and an async variant).
- **`AnalysisFrame`** (`lib/core/models.dart:42-46`): `timestamp`,
  `GrayscaleBitmap bitmap`, `ColorBitmap? color`. `GrayscaleBitmap{width, height,
  gray: Uint8List}` — the tamper detector can work purely off the grayscale plane.
- **`DetectionResult`** (`lib/core/models.dart:132-142`): `timestamp, triggerType,
  score, triggered, detections`. **No detail/qualifier field.**
- **Emission**: `pipeline._maybeEmit` (`lib/detection/pipeline.dart:98-109`)
  enforces per-detector cooldown and pushes `TriggerEvent{timestamp, triggerType,
  score, detectorId}` (`lib/core/models.dart:144-153`).
- **Alert text**: `_alertText` in `lib/event/event_pipeline.dart:117-123` renders
  `'<label> detected in <camera> at <time>'`; `triggerLabel` (`:126-145`) is a
  switch, `'Activity'` default.
- **Detectors to copy from**: `lib/detection/motion_detector.dart` (grayscale
  diff, cached state) and the audio detectors' persistence pattern.

## Design

### 1. Trigger type + `detail` plumbing

- `TriggerType.tamper = 'tamper'` added (`lib/core/models.dart`).
- `DetectionResult` gains `String? detail` (default null); `TriggerEvent` gains
  `String? detail`; `pipeline._maybeEmit` copies `result.detail` into the event.
- `_alertText` (`event_pipeline.dart`) uses a single-trigger batch's
  `detail` when present: `detail == 'covered'` → "Camera covered", `'moved'` →
  "Camera moved" (still in the `<camera> at <time>` shape). Label switch gains
  `tamper → 'Tamper'`; settings `_label` and events `_iconFor` gain tamper too
  (via the shared label map from the dog-audio workstream).
- `RecordedEvent` stays unchanged in v1 (row label shows "Tamper").

### 2. `TamperDetector implements FrameDetector` (v1 heuristic)

Self-contained, working off `frame.bitmap`:

- **Baseline learning (warm-up):** the first `warmUpFrames` frames (constructor
  param, default ~60 frames ≈ 15 s at 4 fps) build a baseline: mean luma `μ`,
  standard deviation `σ`, and a coarse **grid signature** — the analysis frame is
  downsampled to an 8×8 grid of cell means (pure averaging over `gray`). Not armed
  during warm-up.
- **Covered:** sustained `persistenceFrames` frames where mean luma
  `< max(0.03, μ − 3σ)` (near-black floor) → trigger with `detail: 'covered'`,
  `score = (μ − meanLuma) / μ` clamped to 0..1.
- **Moved:** per frame compute (a) `cellChange` = fraction of the 64 cells whose
  mean differs from baseline by > `cellDelta` (e.g. 0.08), and (b) an internal
  **frame-to-frame motion** measure = mean abs diff between consecutive frames.
  Trigger when `cellChange` exceeds `config.threshold` sustained for
  `persistenceFrames` **and** frame-to-frame motion stays low (below `motionFloor`,
  e.g. 0.01) — a persistent scene change with no expected motion ⇒ camera moved.
  `score = cellChange`.
- **Reset:** `reset()` clears baseline + persistence; warm-up restarts (also
  cleared on region/size change via the pipeline's region fan-out if the detector
  keyed its grid off a changed size).
- **Interactions:** works with inclusion/exclusion regions as other detectors do
  (`regions`/`exclusionRegions` filter its trigger area in the coarse grid — v1
  ignores exclusion of the grid if complexity warrants; default: apply exclusion by
  excluding masked cells from both baseline and comparison).
- Defaults: `DetectorConfig(type: tamper, enabled: false, threshold: 0.5,
  persistenceFrames: 3, cooldown: 120s, motionGated: false, default routing)`.
  Added to `AppSettings.defaults()`. Registry entry in `registries.dart`.

### 3. Known limitations (accepted for v1)

- "Moved" is self-contained (its own cheap inter-frame motion), not correlated with
  the pipeline's MotionDetector state — a slow pan could still read as "moved".
  Correlating with the motion detector's firing state is a documented future
  refinement.
- Warm-up assumes a static scene at startup; a camera that starts while already
  tampered needs the covered/black branch to fire (near-black floor catches this).
- No model/image-signature matching; coarse 8×8 grid only.

## Verification

- **Unit tests** (`test/tamper_detector_test.dart`) with synthetic
  `GrayscaleBitmap` frames:
  - warm-up: no trigger during first `warmUpFrames` even on dark frames;
  - covered: sustained near-black frames → `detail == 'covered'`, correct score;
  - moved: persistent cell-diff with low inter-frame motion → `detail == 'moved'`;
  - moved suppressed when inter-frame motion is high (person walking);
  - reset re-arms after baseline.
- **Pipeline test**: a `tamper` detector wired in emits `TriggerEvent` with
  `detail`, cooldown respected.
- **Event text test**: single-trigger batch with `detail 'covered'` → "Camera
  covered …".
- **Existing suite**: `flutter test` + `flutter analyze` green on Linux desktop.

## Deferred / not in this phase

- Correlation with the MotionDetector firing state (shared state between
  detectors).
- Two-way image-signature matching / perceptual hashing.
- Tamper during active monitoring only vs. while monitoring is *paused* by the
  schedule workstream (that is a separate "camera offline" concern for the health
  watchdog — W7).

## Risks

- Medium (heuristic). False positives on lightning changes (mitigated by the
  "low inter-frame motion" discriminator and persistence) and false negatives on
  slow manipulation. Mitigations are testable with synthetic frames and the
  detector is off by default.