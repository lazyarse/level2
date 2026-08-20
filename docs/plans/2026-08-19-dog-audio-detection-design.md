# Dog Audio Detection (Bark + Growl) — Design

Date: 2026-08-19
Status: Draft (implementation plan follows)

## Goal

Add dog-bark and growl audio detection as first-class, routable trigger types,
reusing the existing per-type audio detector architecture. Two trigger types
(`dog_bark`, `growl`) with separate routing, thresholds, cooldowns, and labels —
no new model; YAMNet already scores the relevant AudioSet classes.

## Current state (verified from code, 2026-08-19)

- **One shared YAMNet inference per window.** `DetectorPipeline.processAudio`
  (`lib/detection/pipeline.dart:90-96`) calls `classifier.classify(window)` once,
  then fans the `AudioEventScores` out to every `AudioDetector.analyzeScores()`.
- **Detectors are a 50-line template.** `BabyCryDetector` / `GlassBreakDetector` /
  `LoudNoiseDetector` (`lib/detection/baby_cry_detector.dart`,
  `glass_break_detector.dart`, `loud_noise_detector.dart`) each implement
  `AudioDetector` (`lib/core/detector.dart:92-94`): read one score key, apply a
  `persistenceFrames` counter above `config.threshold`, emit `DetectionResult`.
- **Registry is keyed by trigger type** (`lib/core/registries.dart:18-25`):
  `motion`, `baby_cry`, `glass_break`, `loud_noise`, `face`, `person`.
- **TriggerType is a string-constant class** (`lib/core/models.dart:75-85`):
  `motion`, `baby_cry`, `glass_break`, `loud_noise`, `merged`, `person`, `face`.
- **Score keys live in the classifier.** `YamnetAudioEventClassifier.scoresFromClasses`
  (`lib/detection/audio/yamnet_audio_event_classifier.dart:134-155`) maps the
  521-class vector to per-type keys. Current keys: `'baby_cry'`, `'glass'`,
  `'loud_noise'`. Class indices are constants at lines 24-25 (`babyCryClass = 20`,
  `glassClasses = [435, 437, 463, 464]`).
- **Labels are switch statements.** `triggerLabel()` in
  `lib/event/event_pipeline.dart:126-145`, `_label()` in
  `lib/ui/settings_screen.dart:794-803`, `_iconFor()` in
  `lib/ui/events_screen.dart:127-135` — every new trigger type must be added to
  all three.
- **Simulated audio is signal-synthesized.** `SimulatedAudioSource`
  (`lib/sensors/simulated_audio_source.dart`) has `AudioScene { silence, babyCry,
  glassBreak, bang }` (line 8) and `generateWindow` (lines 39-65) using seeded RNG.
  `MockAudioEventClassifier` (`lib/detection/audio/audio_classifier.dart:25-72`)
  derives the same score keys from RMS + zero-crossing heuristics. Neither has any
  canine signal today.
- **Labels file exists:** `assets/yamnet_labels.txt` (521 lines, index = line − 1).
  Canine classes (verified): **Dog=69, Bark=70, Yip=71, Howl=72, Bow-wow=73,
  Growling=74, Whimper(dog)=75, Canidae=117**.

## Design

### 1. Trigger types and class mapping

Add to `TriggerType` (`lib/core/models.dart`):
- `static const dogBark = 'dog_bark';`
- `static const growl = 'growl';`

YAMNet class mapping (max-fuse like `glassClasses`):
- **bark** → `[69 Dog, 70 Bark, 71 Yip, 73 Bow-wow]`
- **growl** → `[74 Growling]`

New score keys `'dog_bark'` and `'growl'` added to `scoresFromClasses`
(and to the `MockAudioEventClassifier` map).

### 2. Detector classes

Two new `AudioDetector` subclasses copied from the glass-break template:
`DogBarkDetector` (`triggerType => TriggerType.dogBark`, `scoreOf('dog_bark')`)
and `GrowlDetector` (`triggerType => TriggerType.growl`, `scoreOf('growl')`).
Persistence semantics identical (consecutive frames above threshold for
`persistenceFrames` → trigger; counter resets on `reset()`).

### 3. Registry, defaults, labels

- `detectorRegistry` gains `TriggerType.dogBark` and `TriggerType.growl`
  entries (`lib/core/registries.dart`).
- `AppSettings.defaults()` (`lib/core/settings.dart:139-195`) gains disabled-by-default
  `DetectorConfig`s for both (threshold 0.5, persistenceFrames 2, cooldown 60 s,
  `routeToChannelIds` matching the other audio detectors, motionGated false).
- Labels: `triggerLabel`, settings `_label`, and `_iconFor` (e.g. `pets` /
  `dog`-ish icon) updated for both types.
- **Centralization (cross-cutting):** move the per-type label strings into a
  single `TriggerType.label()` map (or shared helper) and have all three sites
  (event text, settings card, events icon) consult it, so future types (tamper,
  health) cannot be missed. Existing call sites are updated in the same commit.

### 4. Simulated audio + mock classifier

- `AudioScene` gains `dogBark` and `growl` values; `generateWindow` synthesizes:
  - `dogBark` — short bursts (200-500 Hz sine bursts, ~100 ms on / ~150 ms off,
    decaying envelope, low-ish RMS) to resemble repeated barks.
  - `growl` — sustained low rumble (80-160 Hz saw/sine, slow amplitude wobble).
- `MockAudioEventClassifier` derives the two new keys from signal features:
  - `dog_bark` — periodic bursty energy (RMS envelope modulation at ~2-4 Hz) with
    moderate RMS.
  - `growl` — low-frequency-dominant sustained energy (high RMS, low zero-crossing
    rate).
- Monitor screen dropdown (`lib/ui/monitor_screen.dart` `_sceneLabel`) gains the
  two new scene labels.

## Verification

- **Unit tests** (`test/audio_detectors_test.dart`): bark/growl persistence,
  threshold, and no-trigger-on-silence; `test/yamnet_audio_event_classifier_test.dart`:
  class-index → `'dog_bark'`/`'growl'` mapping incl. max-fuse across the bark set;
  `test/pipeline_test.dart`: simulated `dogBark`/`growl` windows produce the right
  trigger types; `test/settings_test.dart`: new default configs JSON round-trip.
- **Existing suite**: `flutter test` + `flutter analyze` green on Linux desktop.

## Deferred / not in this phase

- Howl / whimper / yelp as separate trigger types (folded into bark per the
  two-type decision; `Howl=72` and `Whimper=75` are deliberately excluded from the
  bark max-fuse so a howl does not fire a bark alert).
- Cat/animal classes (76-80) — no cat detector requested.
- Per-breed or per-dog differentiation (requires recognition, see roadmap doc).

## Risks

- Low. No new model, no native changes, no pipeline restructure — the change is
  confined to new detector instances + score keys + labels. Main risk is the mock
  classifier's synthetic features not cleanly separating bark vs growl; covered by
  scene tests and adjustable tuning constants.