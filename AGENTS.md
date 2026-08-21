# AGENTS.md

Native Android security-camera app in `level1/android/` (100% Kotlin + Jetpack Compose,
package `io.securitycam.level1`); the Flutter tree was removed after the Phase 7 cutover
(2026-08-21). Design docs in `docs/plans/`; the Dart→Kotlin test mapping lives in
`docs/plans/2026-08-20-native-kotlin-parity-matrix.md`. Emulator-based instrumentation
suite under `level1/android/app/src/androidTest/`, driven by
`level1/tool/run_android_integration_tests.sh`.

## Commands

- Run `date -R` before every command.
- **All Gradle builds** run from `level1/android/` with both env prefixes:
  `ANDROID_HOME=/home/tpa/code/android-env/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.
  The native-assets toolchain locates the NDK via `ANDROID_HOME` (NOT `ANDROID_SDK_ROOT`;
  the shell only exports the latter), and host default Java is 25 which Gradle 8.14 refuses.
  Examples:
  - Unit suite: `ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:testDebugUnitTest`
  - Debug APK: `ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:assembleDebug`
  - Instrumentation (or use the runner below, which sets nothing itself):
    `ANDROID_HOME=... JAVA_HOME=... level1/tool/run_android_integration_tests.sh <serial> <fqcn|all>`
- **Cap command timeouts tightly so hangs surface fast** (full unit suite ~45 s; cold
  Gradle build ~2 min):
  - Gradle build/test commands: **5 min max** (`timeout 300 ./gradlew ...`). Never use
    10–15 min timeouts — a hanging test should fail the command in minutes.
  - adb/emulator operations (install, boot wait, UI automation): **2–3 min max per step**
    (the runner script itself needs a ≥900 s budget because the motion poll alone allows
    6 min).
  - If a timeout fires, diagnose the hang before rerunning; don't just raise the timeout.
- Parse unit failures from `android/app/build/test-results/testDebugUnitTest/TEST-*.xml`
  (python ElementTree) instead of scrolling Gradle output.

## Dev/test target preference

Prefer the fastest platform that can validate the change:

1. **JVM unit tests** (`./gradlew :app:testDebugUnitTest`, Robolectric for Compose UI) —
   instant, no emulator; covers detectors, pipeline, channels, storage, Settings/UI logic.
   Robolectric has no Keystore/Room-server: inject fakes via the view-model factories
   (`SecurityCamApp(eventsFactory=…, settingsFactory=…)` pattern).
2. **`pixel_28_aosp`** — on-device instrumentation when native behavior must run (min-API
   baseline checks; the leanest AOSP image). minSdk is 28 because MediaPipe's tasks-vision
   JNI needs `aligned_alloc` (bionic API 28) plus `strtod_l`/`newlocale` (API 26); every
   x86_64-capable release carries both.
3. **`pixel_34_aosp`** — only when the task is API-34-specific (foreground service type,
   notification runtime permission, MediaStore `RELATIVE_PATH`, camera capabilities).

## Emulator discipline

- Run via `level1/tool/run_android_integration_tests.sh <serial> <fqcn|all>` (waits for
  boot, grants CAMERA/RECORD_AUDIO/POST_NOTIFICATIONS via `pm grant`, coordinates the
  screen-off test through `[itest]` logcat markers, parses the final `OK (N tests)` line).
  Set `EXPECT_CLIP_AUDIO=true` in the environment to assert clip audio tracks too.
- Use the **AOSP system image**, never Google-APIs (`pixel_34`): the `google_apis` image's
  System UI wedges under load (ANR → package service dies → streamed install fails with
  "Broken pipe"), especially headless. Launch headless in the background:
  `nohup <sdk>/emulator/emulator -avd <pixel_28_aosp|pixel_34_aosp> -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &`
- Do **not** run two emulators at once, nor the two images in parallel — shut one down
  before launching the other.
- Before running any emulator test, verify the host has enough free resources:
  RAM at least 4 GiB available (`free -h`) and load average below ~75% of core count
  (`cat /proc/loadavg`). Otherwise poll every 5 minutes until resources free up.
- **After tests complete, kill the emulator and qemu processes**: `adb -s <serial> emu kill`,
  then `pkill -9 -f qemu-system`; verify with `pgrep -c qemu-system` (expect 0). Note:
  `pkill` can wedge the calling shell — follow it with a separate short-status check.
