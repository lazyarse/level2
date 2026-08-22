# Face Recognition (known/unknown triggers) — Design

> Status: Draft (implementation plan follows in
> `2026-08-21-face-recognition-plan.md`). Roadmap-future item from the master
> design, now specified natively against the Kotlin tree.

**Goal:** Identify detected faces against user-enrolled people and expose
distinct **known-face** and **unknown-face** triggers, each independently
routable to channels (e.g. "notify only about strangers").

## Current state verified from code

- `FaceDetector` (`detection/face/FaceDetector.kt`) detects normalized face
  boxes via MediaPipe (`blaze_face_short_range.tflite` asset), applies
  inclusion/exclusion region filtering, then fires `TriggerType.face`.
- Inference runs on **LiteRT 2.2.0** (`CompiledModel`, see
  `inference/LiteRt.kt`, `YoloPersonEngine.kt` for the load/instantiate
  pattern); assets ship via `TfliteAssets.loadModelFile`.
- `TriggerEvent.detail` already carries free-form qualifiers (tamper
  covered/moved) and surfaces in alert text through dedicated label helpers in
  `event/EventPipeline.kt` (`triggerLabel`, `tamperDetailLabel`).
- Channel routing keys off `detectorConfigs[type].routeToChannelIds`;
  `detectorId` on each trigger selects the config.
- Region filtering happens **before** any downstream work, so exclusion zones
  already guarantee faces inside privacy zones never reach recognition.

## Non-goals

- No cloud APIs; everything on-device.
- No liveness/anti-spoofing in this phase (photo-of-print attack acknowledged).
- No automatic clustering/gallery labeling of past events.
- No re-identification across restarts beyond persisted centroids (i.e. no
  raw sample images are retained).

## Design

### 1. Model & embedding engine

- Bundled asset `mobilefacenet.tflite` (5,233,552 bytes, SHA-256
  `be4bc7cf…54854`, sourced from pub.dev `face_detection_tflite` 6.8.0 — the
  same package the Flutter app used; signature verified
  `[1,112,112,3]` float32 → `[1,192]`). New `FaceEmbeddingEngine` mirrors
  `YoloPersonEngine`: lazy `CompiledModel`, `init()`/`dispose()` lifecycle,
  pure function `embed(colorFrame, box): FloatArray` — crops the box
  (square-padded to keep aspect), resizes to 112×112, normalizes pixels to
  [-1, 1].
- Cosine similarity/distance helper in the engine (embedding L2-normalized).

### 2. Identity storage

- `AppSettings.knownFaces: List<KnownFace>` — `{id, label}` records only
  (JSON round-trip like other settings fields; small).
- Centroids live outside settings: `filesDir/known_faces/<id>.bin`, one
  little-endian float array (running mean of sample embeddings, re-normalized
  after each merge). Delete on person removal.
- Enrollment writes merge `new = normalize((n·mean + e) / (n+1))`.

### 3. Triggers & routing

- New `TriggerType.faceKnown = "face_known"`, `TriggerType.faceUnknown =
  "face_unknown"`; `triggerLabel` → "Known face"/"Unknown face".
- Recognition **on**: `FaceDetector` emits `faceKnown` (with `detail` = the
  matched label) or `faceUnknown` (`detail = null`) using `detectorId` equal
  to the emitted type, so each gets its own `DetectorConfig`
  (threshold/persistence/cooldown/routes).
- Recognition **off** (default): unchanged `face` behavior.
- Enabling the feature in Settings seeds `faceKnown`/`faceUnknown` configs by
  copying the existing `face` config (or sensible defaults when absent);
  disabling removes them and restores `face`.

### 4. Matching semantics

- Match = min cosine distance over centroids; threshold default **0.65**
  stored on the `faceKnown` config's `threshold` (distance semantics,
  documented in UI copy).
- No enrolled people ⇒ every face is `unknown`. Feature toggle with zero
  enrollments is allowed (useful stranger alarm).

### 5. Enrollment UX (v1: from the live preview)

- Monitor screen gains an "Enroll face" action (visible while monitoring and
  recognition enabled): the service tags the next analyzed frame containing a
  sufficiently large face (box ≥ 0.12 frame width), computes the embedding
  in-process, and hands it to the settings draft; a label dialog completes
  enrollment (create new person or append to existing).
- Settings → Face recognition section lists enrolled people (label +
  sample count), rename/delete.
- Past-event enrollment deferred.

### 6. Pipeline placement & performance

- Embedding runs only for the highest-confidence face per frame (one extra
  ~5 ms inference), gated behind the existing motion gate and face
  persistence — no pipeline structural changes.

## Verification

- Engine tests: crop/normalize math on synthetic bitmaps; cosine distance;
  centroid merge arithmetic.
- `FaceDetector` tests with mocked engines: known → `faceKnown`+label detail;
  unknown → `faceUnknown`; no enrollments ⇒ unknown; recognition disabled ⇒
  legacy `face`; exclusion zones suppress recognition entirely.
- Store tests: save/load/rename/delete round-trip incl. missing-bin tolerance.
- Settings tests: `knownFaces` round-trip, enable/disable config migration.
- UI tests (Robolectric): settings section renders/enrolls/deletes; monitor
  action visibility rules.
- Existing suites stay green; staging instrumentation pass at the end.

## Deferred / not in this phase

- Liveness detection; multiple-face-per-frame identification; event-snapshot
  enrollment; embedding export/import for backup; pose-aligned cropping.

## Risks

- Model asset size (+~5 MB APK) — acceptable; shrink pass unaffected.
- JNI/model-version drift between LiteRT releases — pinned 2.2.0, smoke test.
- False accepts at 0.65 threshold — tunable per-install via config threshold;
  documented trade-off in settings help text.
