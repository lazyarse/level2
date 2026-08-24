# Loitering Detector — Design

Date: 2026-08-24
Status: Draft

## Goal

Alert when a person remains in view (inside an inclusion region) longer than a
configurable dwell time — the classic "someone is hanging around" signal that
plain person detection can't express. Zero extra model inference: reuses boxes
the person pipeline already computes.

Decisions (2026-08-24):

- Duration-based on top of the shared YOLO26n person engine, not a new model.
- New optional `DetectorConfig.dwellSeconds` (default 10) rather than overloading
  `threshold`/`persistenceFrames`.
- Re-arm semantics: one alert per loiter episode; clock resets only after the
  person has been absent for a grace period.

## Current state (verified from code, 2026-08-24)

- `PersonDetector` (`detection/person/PersonDetector.kt`) is motion-gated,
  runs `analyzeFrameAsync` via `YoloPersonEngine`, filters boxes through
  `RegionFilter.rectOverlapsAny` / exclusion check, then applies threshold +
  `persistenceFrames`.
- `DogDetector` / `CatDetector` show the established pattern for wrapping an
  engine in a persistence-based `FrameDetector`.
- `DetectorConfig` (`detection/Detector.kt`) has fixed fields with JSON blob
  parity (`fromJson` tolerates unknown keys; adding a key is backward-safe for
  legacy Dart blobs).
- The pipeline instantiates detectors per config from `DetectorRegistry`
  factories; engines are cheap to share (`YoloModelSingleton` ref-counts).

## Design

### 1. Config field

```kotlin
// DetectorConfig (+ copyWith/toJson/fromJson):
val dwellSeconds: Int = 10          // json key: "dwellSeconds"
```

Settings UI: for `TriggerType.loitering` only, a stepper row "Dwell time:
Ns" bounded 3…120 s, next to the existing persistence/cooldown steppers.

### 2. `LoiteringDetector` (`detection/person/LoiteringDetector.kt`)

```kotlin
class LoiteringDetector(
    override val config: DetectorConfig,
    engine: PersonEngine? = null,
) : FrameDetector() {
    // state: presentFrames, absentStreak, loiterActive, armedAt
}
```

Behavior per analysis frame (async path, like PersonDetector):

1. Detect persons (shared engine), region-filter exactly like PersonDetector.
2. If a qualifying box overlaps an inclusion region:
   - `absentStreak = 0`; increment continuous-presence time.
   - When presence ≥ `dwellSeconds` and not yet fired this episode → emit
     triggered result with score = box confidence; set `loiterActive`.
   - While `loiterActive`, stay quiet until the episode ends.
3. If no qualifying box: `absentStreak += frameInterval`; once absence exceeds
   **gracePeriod (3 s)** → reset presence clock and clear `loiterActive`
   (re-arms). Brief occlusions (< grace) keep the accumulated dwell.
4. `reset()` clears all state (start/stop boundary); cooldown still applies via
   the pipeline's shared map.

Frame-interval estimation: use timestamp deltas of consecutive frames rather
than assuming 250 ms, so throttling changes don't skew dwell.

### 3. Wiring

- `TriggerType.loitering = "loitering"`; `DetectorType.Loitering` label
  "Loitering", icon `Icons.Filled.Timer`; registry entry building the detector
  with its own `YoloPersonEngine` instance (model load shared via singleton).
- Defaults: enabled=false, threshold=0.5, motionGated=true,
  dwellSeconds=10, routeToChannelIds=["telegram"].
- SettingsScreen: add to `cameraDetectorTypes`.

### 4. Alert text

Detail string `"loitered Xs"` threaded via `DetectionResult.detail` so events
read "Loitering detected in Hallway at … (loitered 12s)" using the existing
detail plumbing (same mechanism tamper uses).

## Verification

- Pure unit tests with `MockPersonEngine`: fires only after cumulative dwell;
  grace period keeps the clock across short gaps; re-arm after real absence;
  region/exclusion filtering respected; detail carries seconds; cooldown honored.
- Settings test: dwellSeconds round-trips through JSON blob (legacy blobs without
  the key default to 10).
- Manual on-device: stand in frame ~10 s → single alert; step out and return →
  second alert after another dwell window.

## Risks

- Person detector misses (side-on poses) pause accumulation rather than reset it
  (grace covers this) — worst case alerts slightly late.
- Two people alternating visibility read as one continuous presence — acceptable;
  splitting identities needs tracking (deferred).
