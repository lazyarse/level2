# Face Recognition — Implementation Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [x]`)
> syntax for tracking. One commit per task; unit suite green before every commit.
> Adapt file-level details to what the code actually looks like mid-task (as done
> in the privacy-zones port); keep semantics per the design doc.

**Goal:** On-device face identification with independently routable known/unknown
face triggers and live-preview enrollment.

**Spec:** `docs/plans/2026-08-21-face-recognition-design.md`

**Execution rule:** JVM unit tests first (Robolectric for Compose/UI);
staging instrumentation pass at the end. Gradle caps 5 min; emulator steps 2–3 min.

---

### Task 1: Model asset + embedding engine

- [x] **Step 1:** Obtain `mobile_face_net.tflite` (pinned source, 112×112 RGB →
  192-d float), add to `app/src/main/assets`; record SHA-256 here.
- [x] **Step 2:** `inference/FaceEmbeddingEngine.kt` following
  `YoloPersonEngine`'s CompiledModel pattern: `init()/dispose()`,
  `embed(frame: ColorBitmap, box: NormalizedBox): FloatArray`
  (square-pad crop → resize 112×112 → normalize to [-1,1] → L2-normalize output),
  plus `cosineDistance(a, b)`.
- [x] **Step 3:** Tests — crop/pad math on synthetic bitmaps (box near edges,
  non-square boxes), output dimension, distance identities (d(x,x)=0,
  d(x,-x)=2), JVM-only path via a fake `CompiledModel` seam.
- [x] **Step 4:** Verify (`testDebugUnitTest`) + commit.

### Task 2: Identity storage

- [x] **Step 1:** `core/KnownFace.kt` — `{id, label}` data class;
  `AppSettings.knownFaces` field with copy/toJson/fromJson (missing key → empty).
- [x] **Step 2:** `identity/KnownFaceStore.kt` — centroids in
  `filesDir/known_faces/<id>.bin`: `enroll(id, embedding)` (running-mean merge,
  re-normalized, returns sample count), `load(id): FloatArray?`, `delete(id)`,
  tolerant of missing/corrupt bins.
- [x] **Step 3:** Tests — settings round-trip incl. legacy JSON; store
  save/load/delete round-trip; corrupt-bin tolerance; merge math.
- [x] **Step 4:** Verify + commit.

### Task 3: Triggers, labels, config migration

- [x] **Step 1:** `TriggerType.faceKnown/faceUnknown`; `triggerLabel` entries.
- [x] **Step 2:** Enable/disable migration helpers in `SettingsRepository`/
  companion (where defaults live): enabling seeds both configs from the current
  `face` config (defaults when absent), disabling removes them and restores
  `face`. Idempotent both ways.
- [x] **Step 3:** Tests — labels; migration from-with/without-face-config
  states; disable/re-enable round-trips; unknown-type routing untouched.
- [x] **Step 4:** Verify + commit.

### Task 4: Detector recognition branch

- [x] **Step 1:** `FaceDetector` gains optional recognition collaborators
  (embedding engine + store + knownFaces lookup, injectable for tests).
  After region filtering: if enabled and faces remain, embed best box, match
  min-cosine-distance over centroids; emit `faceKnown` (detail=label) or
  `faceUnknown`; persistence/cooldown keyed by emitted type. Disabled or no
  enrollments ⇒ legacy `face` behavior; no enrolled people ⇒ always unknown.
- [x] **Step 2:** Tests (mock engines, pixel-coordinate boxes on ≥100 px frames):
  known-match emits label detail; below-threshold match ⇒ unknown; empty store ⇒
  unknown; disabled ⇒ `face`; exclusion-zone face never reaches the embedder.
- [x] **Step 3:** Verify + commit.

### Task 5: Enrollment capture flow

- [x] **Step 1:** Service-side hook: when monitoring and an enrollment request
  is pending (`FaceEnrollmentCoordinator.request()`), tag the first analyzed
  frame whose largest face box ≥ 0.12 frame width; compute embedding via
  Task 1 engine; complete the pending request with `(embedding, sampleCount)`.
  Timeout after N seconds ⇒ failure result.
- [x] **Step 2:** Coordinator API used by UI: `request(): Deferred<Result>`
  + cancel-on-stop semantics (stop() clears pending requests).
- [x] **Step 3:** Tests — coordinator resolves only on qualifying frames
  (fake frame source), timeout path, stop clears pending.
- [x] **Step 4:** Verify + commit.

### Task 6: UI — settings section + monitor enrollment action

- [x] **Step 1:** Settings → Face recognition section: enable toggle (drives
  Task 3 migration), people list (label, samples), delete, rename.
- [x] **Step 2:** Monitor screen "Enroll face" action while monitoring &&
  recognition enabled → coordinator capture → label dialog (new person /
  append existing) → store write + settings update.
- [x] **Step 3:** Tests (Robolectric compose): section render/enroll/delete
  flows with fakes; monitor action visibility rules.
- [x] **Step 4:** Verify + commit.

### Task 7: Final verification + docs

- [x] Full unit suite green; `assembleDebug`.
- [x] Staging instrumentation pass on `pixel_34_aosp`
      (`run_android_integration_tests.sh <serial> all`); kill emulator/qemu.
- [x] Release smoke if build rules changed (asset keeps).
- [x] Tick this plan; add parity-matrix rows; note model SHA-256 in design doc.
