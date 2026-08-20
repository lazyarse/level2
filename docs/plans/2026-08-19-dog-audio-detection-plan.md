# Dog Audio Detection (Bark + Growl) — Implementation Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [ ]`)
> syntax for tracking. This plan is part of the multi-workstream feature batch
> (2026-08-19); execute after go-ahead.

**Goal:** Add `dog_bark` and `growl` as routable trigger types with per-type
detectors, score keys, defaults, labels, and simulated audio — reusing the existing
per-type audio detector architecture.

**Architecture:** Two new `AudioDetector` subclasses consuming two new score keys
(`'dog_bark'`, `'growl'`) produced by both the YAMNet classifier (class-index
max-fuse) and the mock classifier (signal heuristics). TriggerType constants,
registry entries, default configs, and labels added. The label strings are
centralized into a single map so future trigger types cannot be missed.

**Spec:** `docs/plans/2026-08-19-dog-audio-detection-design.md`

**Execution rule:** Prefer Linux desktop (`flutter test`) for all iteration; the
change is pure Dart — no native code, no Android-only behavior.

---

### Task 1: Trigger types, score keys, and the shared label map

**Files:**
- Modify: `security_cam/lib/core/models.dart`
- Modify: `security_cam/lib/detection/audio/yamnet_audio_event_classifier.dart`
- Modify: `security_cam/lib/detection/audio/audio_classifier.dart`
- Modify: `security_cam/lib/event/event_pipeline.dart`
- Modify: `security_cam/lib/ui/settings_screen.dart`
- Modify: `security_cam/lib/ui/events_screen.dart`

- [ ] **Step 1:** Add `TriggerType.dogBark = 'dog_bark'` and `TriggerType.growl = 'growl'`
  (`lib/core/models.dart:75-85`).
- [ ] **Step 2:** In `YamnetAudioEventClassifier` add class-index constants
  `dogBarkClasses = [69, 70, 71, 73]` and `growlClasses = [74]`; extend
  `scoresFromClasses` (`:134-155`) to emit `'dog_bark'` and `'growl'` (max-fuse,
  guarded by `classScores.length` like the existing keys).
- [ ] **Step 3:** Extend `MockAudioEventClassifier.classify` (`audio_classifier.dart:25-72`)
  to emit the two new keys from signal features (bursty RMS modulation → bark;
  low-ZCR sustained energy → growl). Keep constants tunable.
- [ ] **Step 4:** Centralize labels: add `TriggerType.label(type)` (or a shared
  `triggerLabels` map) covering all existing + new types; refactor
  `triggerLabel` (`event_pipeline.dart:126-145`), settings `_label`
  (`settings_screen.dart:794-803`), and `_iconFor` (`events_screen.dart:127-135`)
  to use it. `_iconFor` gains icons for `dog_bark`/`growl`.
- [ ] **Step 5:** Update/add tests: `test/yamnet_audio_event_classifier_test.dart`
  (class indices → `'dog_bark'`/`'growl'`, max-fuse across bark set);
  `test/audio_detectors_test.dart` mock-classifier scene mapping (new scenes →
  new keys); label-map test (every `TriggerType` constant has a label).
- [ ] **Step 6:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: dog bark/growl score keys + centralized trigger labels"
  ```
  Note: this commit will NOT be fully green until Task 2 completes the detector
  classes (analyze will fail on the registry lookup). If it is red, that is
  expected and documented; land it only after Task 2.

### Task 2: Detector classes + registry + defaults

**Files:**
- Create: `security_cam/lib/detection/dog_bark_detector.dart`
- Create: `security_cam/lib/detection/growl_detector.dart`
- Modify: `security_cam/lib/core/registries.dart`
- Modify: `security_cam/lib/core/settings.dart`

- [ ] **Step 1:** Implement `DogBarkDetector` and `GrowlDetector` from the
  `GlassBreakDetector` template (`lib/detection/glass_break_detector.dart`):
  `id => config.type`, `triggerType => TriggerType.dogBark/growl`,
  `analyzeScores` reads `scoreOf('dog_bark')`/`scoreOf('growl')`, identical
  persistence logic.
- [ ] **Step 2:** Add registry entries (`lib/core/registries.dart:18-25`).
- [ ] **Step 3:** Add disabled-by-default `DetectorConfig`s for both types in
  `AppSettings.defaults()` (`lib/core/settings.dart:139-195`), matching the other
  audio detectors (threshold 0.5, persistenceFrames 2, cooldown 60 s, motionGated
  false, default routing).
- [ ] **Step 4:** Tests: `test/audio_detectors_test.dart` — persistence trigger /
  threshold / silence for both detectors; `test/settings_test.dart` — new default
  configs present and JSON round-trip.
- [ ] **Step 5:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: dog bark and growl audio detectors"
  ```

### Task 3: Simulated audio scenes + pipeline test

**Files:**
- Modify: `security_cam/lib/sensors/simulated_audio_source.dart`
- Modify: `security_cam/lib/ui/monitor_screen.dart`
- Modify: `security_cam/test/pipeline_test.dart`
- Modify: `security_cam/test/audio_detectors_test.dart`

- [ ] **Step 1:** Add `AudioScene.dogBark` and `AudioScene.growl` (`simulated_audio_source.dart:8`)
  and `generateWindow` cases (bark = repeated 200-500 Hz decaying bursts;
  growl = sustained 80-160 Hz low rumble with amplitude wobble).
- [ ] **Step 2:** Add dropdown labels in `_sceneLabel` (`monitor_screen.dart`).
- [ ] **Step 3:** Pipeline test (`test/pipeline_test.dart`): `AudioScene.dogBark`
  window → `TriggerType.dogBark`; `AudioScene.growl` → `TriggerType.growl`;
  silence → no trigger. Mock-classifier scene test (`test/audio_detectors_test.dart`)
  for the two new scenes.
- [ ] **Step 4:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: simulated dog bark/growl scenes + pipeline coverage"
  ```

---

## Self-Review notes

- **Spec coverage:** two trigger types ✓; YAMNet max-fuse mapping ✓; mock
  classifier keys ✓; registry + defaults ✓; labels centralized ✓; simulated
  scenes ✓; unit + pipeline tests ✓.
- **Key decision:** howl/whimper/yelp deliberately excluded from the bark max-fuse
  so they don't fire bark alerts (documented in design).
- **Blast radius:** confined to detector instances, score keys, registry,
  defaults, labels, and simulated audio. Pipeline restructure, native code, and
  channel delivery are untouched.
- **Cross-workstream:** the centralized label map from Task 1 is reused by the
  tamper (W6) and health (W7) workstreams.