# Roadmap — Future Possibilities (Docs Only)

Date: 2026-08-19
Updated: 2026-08-24

This document records the two explicitly named future possibilities from the
2026-08-19 feature batch. Neither is planned; they are listed so architecture
 decisions made now do not foreclose them.

## Face Recognition (known-person whitelist) — IMPLEMENTED

Layered recognition on top of the existing face detector, detecting a face
then answering "is this a known resident or an unknown person?".
Implemented 2026-08-22 with embeddings (MobileFaceNet), enrollment UI,
similarity threshold tuning, and on-device storage. See
`docs/plans/2026-08-21-face-recognition-plan.md`.

- **What:** layer recognition on top of the existing face detector (`FaceDetector`,
  `lib/detection/face/face_detector.dart`) — detect a face, then answer "is this a
  known resident or an unknown person?".
- **Why it fits:** face detection already produces normalized boxes; recognition
  would add a per-unknown-face alert and/or suppress alerts for known faces,
  fitting the existing trigger/routing model (e.g. a `knownFace`/`unknownFace`
  trigger type).
- **Notable constraints / open questions:**
  - Needs embeddings (a face-embedding model, e.g. MobileFaceNet via LiteRT) +
    enrollment UI (capture a face → store embedding) + similarity threshold
    tuning. No embedding pipeline exists today.
  - Privacy implications of biometric enrollment on a security device; needs
    explicit user consent + on-device storage story (SecretStore-style handling).
  - On-device cost: face detection runs in the gated path already; embedding +
    comparison adds latency per detected face — must respect the serialized
    analysis pipeline (buffered workstream) to avoid pile-up.
- **Relevant now:** the `detail` field added by the tamper workstream and the
  shared label map make a future `unknown_face` trigger cheap to wire.

## Live Remote Viewing (LAN streaming)

- **What:** stream the live camera feed to another device on the local network
  (e.g. WebRTC or RTSP), surfacing as a "Remote view" mode.
- **Why it fits:** the monitor screen already renders the live `analysisFrames`;
  a stream would re-encode that path (or a higher-res path) to a local endpoint.
- **Notable constraints / open questions:**
  - The Android camera pipeline runs in a native `LifecycleService` (FGS); the
    stream server would need to run in the same service (not the Flutter engine),
    pushing frames over a socket/WebRTC — a native-side component.
  - Requires `ACCESS_LOCAL_NETWORK` permission on Android 16+ (the design doc
    already flags this as future-only).
  - Auth/encryption on the LAN stream (plain RTSP on the local network is a
    privacy risk); bandwidth/battery on-device.
  - Desktop (ffmpeg) and Android (CameraX) have different frame pipelines — the
    stream encoder must sit behind the `CameraSession` contract, not the UI.
- **Relevant now:** keeping the preview on the live broadcast stream (unchanged by
  the buffered workstream) means the UI-side tap point for a future stream remains
  trivial; the heavy lifting is native.

## Cross-cutting notes

- Both features are deliberately deferred; no current workstream depends on them.
- Any new trigger type they add should go through the centralized label map
  introduced by the dog-audio workstream.
- Revisit after the scheduled, privacy-zone, tamper, watchdog, and History
  workstreams have shipped and been exercised on-device.