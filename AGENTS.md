# AGENTS.md

Flutter security-cam app in `level1/` (Android + CameraX), with an emulator-based integration suite under `level1/integration_test/`. Design docs in `docs/plans/`. **Migration in progress (2026-08-20):** the app is being converted to 100% native Kotlin + Compose under `level1/android/` (package `io.securitycam.level1`) per `docs/plans/2026-08-20-native-kotlin-migration-plan.md`; the Flutter tree at `level1/` remains the desktop-only reference harness until the Phase 7 cutover.

## Commands

- Run `date -R` before every command.
- **Prefix with `ANDROID_HOME=/home/tpa/code/android-env/android-sdk` for every Android build** that runs native-assets hooks — that is, any Android build since `face_detection_tflite` was added (`dartcv4`/`opencv_dart` → `package:toolchain`). The native-assets toolchain locates the Android NDK via **`ANDROID_HOME`** (NOT `ANDROID_SDK_ROOT`), and the shell only exports `ANDROID_SDK_ROOT=/home/tpa/code/android-env/android-sdk`. Without it the build fails with `Bad state: No element`. Examples:
  - `ANDROID_HOME=... flutter build apk --debug`
  - `ANDROID_HOME=... flutter test integration_test/<file>.dart -d <serial>` (the tool runner `level1/tool/run_android_integration_tests.sh` also needs it: `ANDROID_HOME=... level1/tool/run_android_integration_tests.sh <serial>`)
  - (Linux desktop builds and `flutter analyze`/unit tests need no prefix.)
- **Prefix with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` for every native Gradle build** in `level1/android/`. The host default `java` is 25.0.4, which Gradle 8.14 refuses to run on (`BUILD FAILED` with the bare version string `25.0.4`). Example:
  - `ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:assembleDebug`

## Dev/test target preference

Prefer the fastest platform that can validate the change:

1. **Linux desktop app** (`flutter test -d linux`, or `flutter run -d linux` for a quick smoke) —
   unit tests + simulated camera/audio run instantly with no emulator; use for iteration,
   pure-Dart logic, Settings/UI, and everything not exercising the native `camera_service`.
2. **`pixel_24_aosp`** — Android on-device integration tests when they must run (min-API 24
   baseline checks; the leanest AOSP image).
3. **`pixel_34_aosp`** — only when the task is API-34-specific (foreground service type,
   notification runtime permission, MediaStore `RELATIVE_PATH`, camera capabilities on API 34).

Avoid emulators unless the change touches native Android behavior.

## Emulator integration tests

- Run via `level1/tool/run_android_integration_tests.sh` (host-driven: waits for boot, grants permissions via `pm grant`, coordinates the screen-off test through `[itest]` markers).
- Use the **AOSP system image**, never the Google-APIs one (`pixel_34`): the `google_apis` image's System UI is heavy and wedges under load (ANR → package service dies → streamed install fails with "Broken pipe"), especially in headless CI. Launch headless in the background: `nohup <sdk>/emulator/emulator -avd <pixel_24_aosp|pixel_34_aosp> -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &`.
- Do **not** run the `pixel_24_aosp` and `pixel_34_aosp` emulators at the same time, nor run the two images in parallel — shut one down before launching the other.
- Before running any emulator test, verify the host has enough free resources:
  - RAM: at least 4 GiB free (see `free -h`, `Mem: available`).
  - CPU: load average (see `cat /proc/loadavg`) below ~75% of core count.
- If the host does not have enough free RAM/CPU, do NOT start the emulator test. Poll `free -h` / `/proc/loadavg` every 5 minutes until resources are available, then proceed.
- **After the tests complete, kill the emulator and qemu processes** (they keep consuming CPU/RAM in the background and can wedge subsequent runs): `adb -s <serial> emu kill` if responsive, then `pkill -9 -f qemu-system` (and `pkill -f 'emulator.*<avd>'` if needed), and optionally `adb kill-server`. Verify nothing lingers with `ps aux | rg 'qemu-system'`.