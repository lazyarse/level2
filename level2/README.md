# Security Cam (level2)

A native Android app (Kotlin + Jetpack Compose, package `io.securitycam.level2`) that turns
a phone into a "security camera": it monitors the camera (and microphone) for motion /
sound / face / person events and alerts the user through pluggable channels (Telegram,
email, Pushover, webhook). Each channel has its own user-defined settings, and each detector
type can be individually enabled, tuned, and routed to specific channels. Recording runs in
a foreground service so monitoring survives screen-off.

**Design/plan:** `docs/plans/` — start at `2026-08-17-security-cam-app-design.md`; the
Flutter→native migration is documented in `2026-08-20-native-kotlin-migration-plan.md`
(the Flutter tree was removed after the Phase 7 cutover).

## Layout

- `android/` — Gradle project: app module with Compose UI, CameraX capture, MediaPipe face
  detection, YOLO person detection, YAMNet audio classification, Room event log.
- `tool/run_android_integration_tests.sh <serial> <fqcn|all>` — emulator instrumentation
  runner (permissions via `pm grant`, `[itest]` marker coordination for the screen-off test).
- `docs/plans/2026-08-20-native-kotlin-parity-matrix.md` — Dart-test → Kotlin-test mapping.

## Getting started

Requires JDK 17 (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` here) and an Android SDK
(`ANDROID_HOME=/home/tpa/code/android-env/android-sdk`):

```sh
cd level2/android
ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:testDebugUnitTest   # JVM suite
ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:assembleDebug       # debug APK
```

On-device integration tests need an AOSP emulator (see `AGENTS.md` for emulator discipline).

## License

AGPL-3.0 (see `LICENSE`). The app bundles YOLO-family / YAMNet-class models which are
AGPL-3.0-compatible.
