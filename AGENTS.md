# AGENTS.md

**Run `date -R` before every command.**

Native Android security-camera app in `android/` (100% Kotlin + Jetpack Compose,
package `io.securitycam.level2`); the Flutter tree was removed after the Phase 7 cutover
(2026-08-21). Design docs in `docs/plans/`; the Dart→Kotlin test mapping lives in
`docs/plans/2026-08-20-native-kotlin-parity-matrix.md`. Emulator-based instrumentation
suite under `android/app/src/androidTest/`, driven by
`tool/run_android_integration_tests.sh`.

## Commands

- **All Gradle builds** run from `android/` with both env prefixes:
  `ANDROID_HOME=/home/tpa/code/android-env/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.
  The native-assets toolchain locates the NDK via `ANDROID_HOME` (NOT `ANDROID_SDK_ROOT`;
  the shell only exports the latter), and host default Java is 25 which Gradle 8.14 refuses.
  Examples:
  - Unit suite: `ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:testDebugUnitTest`
  - Debug APK: `ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:assembleDebug`
  - Instrumentation (or use the runner below, which sets nothing itself):
    `ANDROID_HOME=... JAVA_HOME=... tool/run_android_integration_tests.sh <serial> <fqcn|all>`
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

- Keystore lives outside the repo at `~/.keystores/level2-release.jks`; credentials in
  user-global `~/.gradle/gradle.properties` as `LEVEL2_RELEASE_STORE_FILE`,
  `LEVEL2_RELEASE_STORE_PASSWORD`, `LEVEL2_RELEASE_KEY_ALIAS`,
  `LEVEL2_RELEASE_KEY_PASSWORD`. Without them, release falls back to the debug key.
- Expected release cert (re-keyed 2026-08-25; pre-1.0.1 artifacts were signed by the
  retired `CN=level1` key — see `~/.keystores/level1-release.jks.retired`):
  `CN=level2, OU=SecurityCam, O=security-cam`, SHA-256
  `e686383a468c9ca7964985f84ff337dd1eee442fc91d6c8335819e00dbd6190f`.
- Verify signatures with `apksigner verify --print-certs` (jarsigner cannot see v2/v3).

## Versioning

- `versionName`/`versionCode` derive from git at build time
  (`android/app/build.gradle.kts`): `git describe --tags --dirty` drives the name,
  commit count drives the code. Never hand-edit them — cut a release by pushing a
  `vX.Y.Z` tag.
- Release builds (`assembleRelease`/`bundleRelease`) fail on a dirty working tree;
  staging/debug/unit tests stay permissive.
- Builds outside a git repo fall back to `0.0.0-untagged` / versionCode `1`.
- `.github/workflows/release.yml` checks out with `fetch-depth: 0` so CI sees tags.

## Emulator discipline

- Run via     `tool/run_android_integration_tests.sh <serial> <fqcn|all>` (waits for
  boot, grants CAMERA/RECORD_AUDIO/POST_NOTIFICATIONS via `pm grant`, coordinates the
  screen-off test through `[itest]` logcat markers, parses the final `OK (N tests)` line).
  Set `EXPECT_CLIP_AUDIO=true` in the environment to assert clip audio tracks too.
- Use the **AOSP system image**, never Google-APIs (`pixel_34`): the `google_apis` image's
  System UI wedges under load (ANR → package service dies → streamed install fails with
  "Broken pipe"), especially headless. Launch headless in the background:
  `nohup <sdk>/emulator/emulator -avd <pixel_28_aosp|pixel_34_aosp> -no-snapshot -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &`
- **Always launch with `-no-snapshot`** (cold boot). Quickboot snapshots silently
  restore sick states after crash loops or long sessions: adb responses crawl
  (ddmlib `TimeoutException` inside gradle installs), `pm` wedges (installs take
  minutes then fail with "Failed to install on any devices"), and uiautomator
  dumps hang. If a running emulator shows those symptoms, kill it (`adb emu kill`,
  then verify `pgrep -c qemu-system` → 0) and relaunch cold — do not debug the
  device state first.
- Do **not** run two emulators at once, nor the two images in parallel — shut one down
  before launching the other.
- Before running any emulator test, verify the host has enough free resources:
  RAM at least 4 GiB available (`free -h`) and load average below ~75% of core count
  (`cat /proc/loadavg`). Otherwise poll every 5 minutes until resources free up.
- **After tests complete, kill the emulator and qemu processes**: `adb -s <serial> emu kill`,
  then `pkill -9 -f qemu-system`; verify with `pgrep -c qemu-system` (expect 0). Note:
  `pkill` can wedge the calling shell — follow it with a separate short-status check.

## Emulator virtual camera

The goldfish camera HAL in the Android emulator has two quirks that break CameraX:

1. It does **not** set `LENS_FACING` on any camera, so the default
   `CameraSelector.DEFAULT_BACK_CAMERA` limiter filters it out before CameraX's
   camera repository is populated — `bindToLifecycle()` fails with
   "Invalid use of CameraSelector: no camera available".
2. The camera is assigned ID `"10"` (not `"0"`), which
   `MonitoringService.cameraSelectorFor` already handles via its `else` branch.

**Fix in `Level2App.kt`**: the app implements `CameraXConfig.Provider` and overrides
`getCameraXConfig()`:

```kotlin
CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
    .setAvailableCamerasLimiter(CameraSelector.Builder().build())
    .build()
```

`fromConfig(Camera2Config.defaultConfig())` copies the mandatory Camera2 providers
(CameraFactory, DeviceSurfaceManager, UseCaseConfigFactory). The unfiltered selector
(`CameraSelector.Builder().build()`) accepts all cameras regardless of `LENS_FACING`.

**Virtual camera pipeline** (`tool/take_app_screenshots.sh` sets this up automatically):

```sh
# 1. Load the v4l2loopback kernel module (requires sudo)
sudo modprobe v4l2loopback   # creates /dev/video2, /dev/video3

# 2. Set the capture format — MUST match exactly what ffmpeg writes
v4l2loopback-ctl set-caps /dev/video2 "YUYV:1280x720"

# 3. Stream a scene image into the loopback device
ffmpeg -re -loop 1 -i tool/virtual-scene/scene.jpg \
  -vf "scale=1280:720,format=yuyv422" \
  -f v4l2 /dev/video2

# 4. Launch the emulator with -camera-back webcam1
emulator -avd pixel_34_aosp -no-snapshot -no-window -camera-back webcam1 ...
```

- Goldfish HAL only recognises `webcam1` — higher indices (webcam2, etc.) are
  ignored and produce 0 cameras.
- `v4l2loopback-ctl set-caps` **must** be run before ffmpeg; without it the
  device advertises no fixed format and goldfish HAL rejects it.
- CameraX 1.3.4 is intentionally pinned — 1.4.x breaks Kotlin compilation due
  to a vararg collision with `bindToLifecycle` overload.
- `screencap` captures the live preview when the pipeline is active (the preview
  area is not black).

## Screenshots & gallery

- `tool/take_app_screenshots.sh` captures app screenshots into `docs/images/` and
  merges them into `docs/gallery.md` via `tool/gallery_sync.py`.
- The merge script preserves existing gallery entries, adds entries for newly captured
  screenshots, and removes entries whose images no longer exist on disk.
- Re-run the screenshot script after any Monitor/Events/Settings layout change — the
  gallery self-heals without manual edits.
