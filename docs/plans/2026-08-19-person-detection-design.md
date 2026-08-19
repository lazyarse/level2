# Person Detection — Design

Date: 2026-08-19
Status: Draft (implementation plan pending)

## Goal

Add on-device person detection as an alert trigger to the security-cam app, gated by
motion (Phase 2 of the detection roadmap in `docs/plans/2026-08-17-security-cam-app-design.md`).
Pose detection (Phase 3) is deferred.

## Locked decisions (from Q&A + main design doc)

| Topic | Decision |
|---|---|
| Role | Person detection is an **alert trigger**: only fire when a person is present (replaces bare "motion" as the alerting condition once enabled) |
| Cadence | **Motion-gated**: `PersonDetector` runs only when the existing pixel-diff `MotionDetector` fires, via the same `motionGated` pipeline gate built for face detection. No motion → no person inference (battery sanity). |
| Engine | **YOLO26n person-class model** run over the existing color analysis stream. Model: `yolo26n_w8a32.tflite` (~2.9 MB, Ultralytics official release, exported `format=litert, nms=False, end2end=False, quantize=w8a32`). Main design doc specifies **YOLO26n** via **LiteRT** (AGPL-3.0, compatible). |
| Inference binding | Reuse the project's established LiteRT stack via a **hand-rolled `flutter_litert` wrapper** (`Interpreter.fromAsset` + `Tensor.setTo/copyTo`). Face detection uses `flutter_litert` (works on Android AND Linux desktop — the fast dev loop). Person detection follows the same engine abstraction so the real-model gate can run on Linux, mirroring the face phase. Deviation from the main doc's "tflite_flutter" literal wording: `tflite_flutter` is Android-only here (its Linux CMake target collides with `flutter_litert`), and `flutter_litert` IS the LiteRT runtime — noted in the plan. |
| Model contract (verified from the .tflite FlatBuffer) | Input `[1,3,640,640]` float32 NCHW, RGB, `[0,1]`. Output `[1,84,8400]` float32 channel-major: rows 0–3 = `(cx,cy,w,h)` **normalized [0,1]**; rows 4–83 = per-class sigmoided scores (person = row 4). No built-in NMS. Preprocess: letterbox 640×640 with black (0,0,0) padding. Post-process: decode → `*640` → inverse letterbox → IoU NMS (0.7, max 30). |
| Analysis stream | Reuses the **color analysis stream** + `AppSettings.analysisResolution` preset built in the face phase — no new stream work. |
| Person-specific settings | `DetectorConfig` for `person`: enabled default **off**, motion-gated `true`, own `threshold` (detector confidence), `persistenceFrames`, `cooldown`, `routeToChannelIds`. `_DetectorCard` renders it automatically. |
| Recognition / identity | Out of scope — person detection alerts on any person. |

## Engine abstraction

Mirror the face phase's split so tests stay fast and headless:

- `PersonDetector implements FrameDetector`, `id = 'person'`, `triggerType = TriggerType.person`
  (already exists in `lib/core/models.dart`).
- `PersonEngine` abstraction (like `FaceEngine`): real LiteRT engine on all platforms that
  bundle the model, mock for pure unit tests.
- Input: the color `AnalysisFrame` → BGR bytes + width/height (same as BlazeFace). Output:
  person bounding boxes (+ scores) in frame coordinates.
- Trigger on ≥1 detected person; result score = max person score. Reuse `persistenceFrames`,
  `cooldown`, `routeToChannelIds` via `DetectorConfig`.
- `minScore` gate from `config.threshold`.

## Post-processing

YOLO26n output is a raw `[1, 84, 8400]` tensor (no built-in NMS in this export). The engine:

- Reads the person class channel (row 4) per anchor; skips anchors below `config.threshold`.
- Decodes `(cx, cy, w, h)` (normalized [0,1]) → `x1,y1,x2,y2`, maps back to frame coords via the
  inverse letterbox transform.
- Applies IoU NMS (0.7, max 30) to merge overlapping boxes before deciding trigger state.
- The `PersonDetector` triggers on ≥1 surviving box.

## Integration

- Register `person` in `detectorRegistry` (`lib/core/registries.dart`).
- `triggerLabel` → `'Person'` in `lib/event/event_pipeline.dart`.
- Default `DetectorConfig` for `person` in `AppSettings.defaults()`: disabled by default,
  motion-gated, own threshold/cooldown/routing. Settings UI renders via existing `_DetectorCard`.
- Downstream (TriggerBatcher, EventPipeline, channel routing, snapshots, event log) unchanged.

## Testing

- Unit: mock engine trigger/persistence/score logic; motion-gate tests; `triggerLabel`;
  JSON round-trip of the `person` config.
- **Linux desktop integration with the real YOLO model** on bundled person/no-person images
  (mirrors `face_detection_linux_test.dart`) — exercises the real engine without an emulator.
- Android: extend the on-device harness with a person-enabled scenario (same pattern as the
  face scenario in `monitoring_on_device_test.dart`).

## Deferred — pose detection (Phase 3)

- `PoseDetector` downstream of person; posture/fall detection consumes pose keypoints.
- Model: `YOLO26n-pose` (person boxes + 17 keypoints in one graph) — main design doc; not
  this phase.