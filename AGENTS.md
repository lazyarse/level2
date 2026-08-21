# AGENTS.md

**Run `date -R` before every command.**

Native Android security-camera app in `level1/android/` (100% Kotlin + Jetpack Compose,
package `io.securitycam.level1`); the Flutter tree was removed after the Phase 7 cutover
(2026-08-21). Design docs in `docs/plans/`; the Dart→Kotlin test mapping lives in
`docs/plans/2026-08-20-native-kotlin-parity-matrix.md`. Emulator-based instrumentation
suite under `level1/android/app/src/androidTest/`, driven by
`level1/tool/run_android_integration_tests.sh`.

## Commands

- **All Gradle builds** run from `level1/android/` with both env prefixes:
  `ANDROID_HOME=/home/tpa/code/android-env/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.
  The native-assets toolchain locates the NDK via `ANDROID_HOME` (NOT `ANDROID_SDK_ROOT`;
  the shell only exports the latter), and host default Java is 25 which Gradle 8.14 refuses.
  Examples:
  - Unit suite: `ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:testDebugUnitTest`
  - Debug APK: `ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:assembleDebug`
  - Instrumentation (or use the runner below, which sets nothing itself):
    `ANDROID_HOME=... JAVA_HOME=... level1/tool/run_android_integration_tests.sh <serial> <fqcn|all>`
- **Cap command timeouts tightly so hangs surface fast.** Measured baselines:
  full unit suite ~45 s; cold Gradle build ~2 min (minified release ~4.5 min);
  emulator boot ~30 s; warm APK install ~35 s; the whole instrumentation "all"
  pass typically finishes in **4–6 min**.
  - Gradle build/test commands: **5 min max** (`timeout 300 ./gradlew ...`).
    Never use 10–15 min timeouts — a hanging test should fail the command in
    minutes.
  - adb/emulator operations (install, boot wait, UI automation): **2–3 min max
    per step**.
  - The runner script's own budget: **600 s** (`timeout 600`). Its worst case is
    bounded by the motion poll inside `MonitoringInstrumentedTest`
    (`pollTimeoutMs`, 3 min) plus install and the screen-off sleeps — anything
    beyond ~8 minutes means something hung; kill and diagnose rather than wait.
  - If a timeout fires, diagnose the hang before rerunning; don't just raise the
    timeout.
- Parse unit failures from `android/app/build/test-results/testDebugUnitTest/TEST-*.xml`
  (python ElementTree) instead of scrolling Gradle output.

## Dev/test target preference

Prefer the fastest platform that can validate the change:

1. **JVM unit tests** (`./gradlew :app:testDebugUnitTest`, Robolectric for Compose UI) —
   instant, no emulator; covers detectors, pipeline, channels, storage, Settings/UI logic.
   Robolectric has no Keystore/Room-server: inject fakes via the view-model factories
   (`SecurityCamApp(eventsFactory=…, settingsFactory=…)` pattern).
2. **`pixel_34_aosp` instrumentation vs the minified `staging` build** — the default
   target of `tool/run_android_integration_tests.sh` (override with
   `BUILD_TYPE=debug`). Staging is shrink-only R8 (no obfuscation/optimization; see
   `app/staging-rules.pro`) so the unshrunk test APK can link into it; it exists to
   catch over-shrinking regressions in reflective third-party code (this is how the
   MediaPipe consumer-rules gap was found). Cold first pass may exceed 5 min (two R8
   runs + 90 MB install); prewarm with `:app:assembleStaging
   :app:assembleStagingAndroidTest` when needed.
3. **`pixel_34_aosp` release smoke** after touching build rules or dependencies:
   uninstall debug/staging packages, `adb install app-release.apk`, pm grant the three
   permissions, launch, tap the monitor start button, confirm state reaches
   "Hallway — Monitoring" with no FATAL/link errors in logcat (~2 min).
4. **`pixel_28_aosp`** — min-API baseline checks only (minSdk 28: MediaPipe's JNI needs
   `aligned_alloc`, bionic API 28).
5. Never use Google-APIs images (`pixel_34`); their System UI wedges under load.

## Release signing

- Keystore lives outside the repo at `~/.keystores/level1-release.jks`; credentials in
  user-global `~/.gradle/gradle.properties` as `LEVEL1_RELEASE_STORE_FILE`,
  `LEVEL1_RELEASE_STORE_PASSWORD`, `LEVEL1_RELEASE_KEY_ALIAS`,
  `LEVEL1_RELEASE_KEY_PASSWORD`. Without them, release falls back to the debug key.
- Verify signatures with `apksigner verify --print-certs` (jarsigner cannot see v2/v3).

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
