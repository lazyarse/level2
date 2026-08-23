# Face events & recognition — known limitations

Date: 2026-08-23
Status: accepted for current phase

## 1. One name per merged event

Multiple triggers inside one batch window collapse into a single event row.
When several people are detected simultaneously the recognizer emits multiple
`face_known` triggers, but the merged event carries only the first matching
`detail` — the inline name on the events list therefore shows **one** person
even if two known faces appeared in the same window.

*Future design:* per-person child rows attached to a merged event, or one
event per recognized identity with shared media.

## 2. Detection-box coordinate contracts

Engines own their coordinate space; downstream consumers must match:

| Engine | Emitted space | Notes |
|---|---|---|
| `MediaPipeFaceEngine` | **normalized 0..1** | converted at `detectFaces()` via `normalized()` |
| `YoloPersonEngine` (`PersonBox`) | **pixels** | `PersonDetector` divides by frame dims itself |

History: face boxes were emitted in pixels until 2026-08-23, which silently
broke embedding crops (edge slivers) and thumbnails. If a new engine is added,
normalize at the boundary and update this table.

## 3. Recognition enablement semantics

- Recognition runs only when `detectorConfigs` contains `face_known`
  (`AppSettings.faceRecognitionEnabled`). Enrolling the first person seeds
  these configs automatically and persists them.
- A monitoring session binds its detector set at start: toggling recognition
  or enrolling mid-session requires **restarting monitoring** to take effect.
- The live person roster is served by `FaceDirectory` (updated on enroll/
  delete/save), so a running session does see roster *content* changes once
  the recognizer exists.

## 4. Orientation conventions

- Analysis frames published on `CameraFrameBus` are **display-upright**
  (rotated by `imageInfo.rotationDegrees` before publish).
- Video clips are authored portrait-upright (`ROTATION_0` target) regardless
  of live display state; a mounted/static camera must not inherit screen
  orientation from start time.
