# Dev notes

## Detector sensitivity (1–20 scale)

The settings UI shows sensitivity 1–20 per detector; internally detectors still
compare `score >= DetectorConfig.threshold` (0–1) and the stored JSON keeps the
raw threshold, so old blobs load unchanged. Mapping lives in
`detection/Sensitivity.kt` (`SensitivityScale`), UI-only.

- **Direction:** 20 = most sensitive (lowest internal threshold, most triggers).
- **Formula** (linear within a per-detector band, inverted):
  `threshold = max − (s − 1) / 19 · (max − min)`.

### Threshold bands

| Detector(s) | Band (min–max threshold) | s=1 | s=20 | Shipped default → s |
|---|---|---|---|---|
| motion | 0.005 – 0.20 | 0.20 (least sensitive) | 0.005 (most sensitive) | 0.03 → ~18 |
| all confidence-based (person, face, tamper, bird, livestock, vehicle, loitering, tripwire, baby_cry, glass_break, loud_noise, dog, cat + dog/cat sound `audioThreshold`) | 0.10 – 0.90 | 0.90 | 0.10 | 0.5 → ~10–11; face 0.7 → ~6 |

Motion gets its own narrow band because it is a changed-pixel ratio whose
useful span sits far below 0.5 — on a global map it would bunch up at one end.

### Edge cases

- Out-of-band stored thresholds (e.g. a pre-scale blob with 0.02 on a
  classifier) clamp to 1/20 for display; the raw JSON value is preserved.
- `face_known` / `face_unknown` have no sliders — auto-seeded from the face
  config (`AppSettings.withFaceRecognition`); `face_known` uses the fixed
  `FACE_MATCH_THRESHOLD = 0.65`.
- Slider granularity is 20 steps (`valueRange = 1f..20f, steps = 18`),
  coarser than the old continuous 0–1 slider by design.
- Captions: 1–6 Low · 7–14 Medium · 15–20 High.

## Shared YOLO inference

All six YOLO-backed engines (person, vehicle, dog, cat, bird, livestock)
share one `yolo26n` model (`YoloModelSingleton`), and since 2026-09-16 they
also share one inference per frame (`YoloSharedInference`, keyed on frame
identity): the first engine to see a frame runs the 640×640 CPU model, the
rest reuse the raw output and only pay preprocess + their own class decode.

Why: each engine used to call `compiled.run` itself while
`DetectorPipeline.processFrame` runs gated detectors serially, so N detectors
meant N serial ~570 ms CPU inferences per motion frame on a Galaxy A13
(person+vehicle measured at ~1200 ms/frame, saturating the CPU and stuttering
the preview). Shared: person+vehicle ≈ 630 ms/frame. Enabling more detectors
now adds ~20 ms each (preprocess + decode), not another inference.
