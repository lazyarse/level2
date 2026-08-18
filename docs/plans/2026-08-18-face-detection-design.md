# Face Detection — Design

Date: 2026-08-18
Status: Approved (Aug 18 2026) — design reviewed via brainstorming; implementation plan pending

## Goal

Add on-device face detection as an alert trigger to the security-cam app. Face
detection is the current phase; face recognition (identity) is a deferred future
phase that the design explicitly leaves room for.

## Locked decisions (from Q&A)

| Topic | Decision |
|---|---|
| Role | Face detection is an **alert trigger** now (routes to channels like any detector). Recognition later refines the rule to "unknown face → alert, known → suppressed/tagged" |
| Cadence | **Motion-gated**: the face detector runs only when the existing pixel-diff `MotionDetector` fires (same gate the main design doc plans for Phase 2 person detection) |
| Engine | **BlazeFace via `face_detection_tflite`** (pure-Dart, Apache-2.0, uses the existing `tflite_flutter`/LiteRT stack, cross-platform incl. Linux desktop for the dev loop). Back-camera / full-range model (tuned for group/distant faces) |
| Analysis stream | Single color analysis stream; `AppSettings.analysisResolution` preset sets its size. Default **balanced 320×240 @ 4 fps** (presets: low 160×120 / balanced 320×240 / high 640×480) |
| Recognition (deferred) | `face_detection_tflite` already ships MobileFaceNet 192-d embeddings + `compareFaces` (Apache-2.0) — recognition = enrollment storage (SQLite) + match + "unknown face" alert semantics. Not implemented this phase |

## Prerequisite — color analysis stream

Current `AnalysisFrame` carries only a grayscale 160×120 @ 4 fps bitmap
(`lib/core/models.dart`); face detection, person detection (Phase 2) and
recognition all need color, and 160×120 is too small for far faces.

- Extend `AnalysisFrame` with a `ColorBitmap` (raw BGR pixels + width/height).
  Keep the grayscale field — `MotionDetector` is untouched.
- New `AppSettings.analysisResolution`:
  - `low` = 160×120, `balanced` = 320×240 (default), `high` = 640×480.
  - Fixed 4 fps. JSON round-trip + `copyWith`, following existing settings
    pattern (`lib/core/settings.dart`).
- Producers emit color at the configured size:
  - Android: CameraX `ImageAnalysis` YUV→RGB in the native `camera_service` module.
  - Desktop: `FfmpegCameraSession` and the simulated camera.
- Single stream: the preset sets the size of the one analysis stream used for
  both motion and face detection.

## Motion gating in the pipeline

`DetectorPipeline.processFrame` currently runs every frame detector on every
frame (`lib/detection/pipeline.dart`). No gating exists yet.

- Add `motionGated` (bool, default `false`) to `DetectorConfig`
  (`lib/core/detector.dart`) + JSON round-trip.
- `DetectorPipeline.processFrame` tracks whether `MotionDetector` fired on the
  current frame; gated detectors run only on those frames, receiving the same
  `AnalysisFrame`.
- Add an async analysis path to `FrameDetector`
  (`Future<DetectionResult> analyzeFrameAsync(AnalysisFrame)`); the pipeline
  awaits it for gated detectors. Matches the main design doc's worker-isolate
  offload intent; `face_detection_tflite` runs inference in its own background
  isolate already.
- Motion's `persistenceFrames` re-arms the gate for a few frames of a motion
  event — good for face persistence.

## FaceDetector

- New `FaceDetector implements FrameDetector`, `id = 'face'`,
  `triggerType = TriggerType.face`.
- Runs `face_detection_tflite`'s `FaceDetector` with the back-camera
  (full-range) BlazeFace model on the color analysis frame via
  `detectFacesFromMatBytes` (raw BGR + dims).
- Gates: `minScore` from `config.threshold`; keep `minFacePresenceConfidence`
  default (0.5) — suppresses hand/palm false positives.
- Trigger on ≥1 detected face; result score = max face score. Reuse
  `config.persistenceFrames` (flicker suppression), `config.cooldown`
  and `config.routeToChannelIds` (routing) — all free via `DetectorConfig`.
- Factory `buildFaceDetector()` following `buildAudioClassifier()`
  (`lib/sensors/audio_classifier_factory.dart`): real engine on mobile,
  `MockFaceDetector` for headless unit tests.
- Bundle BlazeFace model(s) as assets (same pattern as `assets/yamnet.tflite`).

## Integration

- `TriggerType.face = 'face'` (`lib/core/models.dart`); register in
  `detectorRegistry` (`lib/core/registries.dart`); `triggerLabel` → `'Face'`
  (`lib/event/event_pipeline.dart`).
- Default `DetectorConfig` for `face` in `AppSettings.defaults()`: disabled by
  default, motion-gated, own threshold/cooldown/routing. The settings screen's
  `_DetectorCard` (`lib/ui/settings_screen.dart`) renders it automatically.
- Add a `motionGated` toggle to `_DetectorCard`.
- New **Advanced** section in Settings with an *Analysis resolution* dropdown
  (Low / Balanced / High) + a note on the battery tradeoff.
- Downstream (TriggerBatcher, EventPipeline, channel routing, snapshots, event
  log) works unchanged.

## Testing

- Unit: `MockFaceDetector` trigger/persistence/score logic; motion-gate tests
  (pipeline runs gated detector only when motion fired); JSON round-trip of
  `motionGated`; `triggerLabel` for `face`.
- Linux desktop integration with the real BlazeFace model on a synthetic face
  image (`face_detection_tflite` supports Linux) — exercises the real engine
  without an emulator.
- Android: extend `run_android_integration_tests.sh` with a face test frame.
- Note the known API-24 `strtod_l` caveat (main design doc) — same mock-fallback
  pattern as YAMNet for old AOSP images.

## Deferred — face recognition (future phase)

- Enrollment: name + stored 192-d MobileFaceNet embedding in SQLite.
- Match on detection via `FaceDetector.getFaceEmbedding` + `compareFaces`
  (cosine similarity; ~>0.5 likely same person).
- Alert rule becomes "unknown face → alert; known → suppressed or tagged".
- The color stream and face boxes from this phase are exactly what recognition
  needs; model license already clean (Apache-2.0).