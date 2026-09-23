# androidTest assets — provenance (test-only, never shipped in the APK)

These images exercise the on-device detectors under instrumentation and are
NOT packaged into any release/debug/staging APK.

- `messi5.jpg` — photo containing people, used by `PersonDetectionTest`
  (expects >= 1 person box) and `Phase3EnginesSmokeTest` asset loading.
- `astronaut.png` — photo containing a person, used by `PersonDetectionTest`.
- `camera.png` — control image, used by `PersonDetectionTest`.

Provenance: UNRESOLVED — carried over from the pre-native (Dart) test corpus
with no recorded source URL, author, or license. TODO before F-Droid
submission: replace all three with self-captured photos (device camera,
no third-party rights) or images with a documented FLOSS-compatible license
(CC0 / CC BY / CC BY-SA with attribution recorded here), then note the
source per file above.
