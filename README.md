# Security Cam (level2)

We believe everyone is entitled to feel safe. This is a free security camera application with advanced features to help you set up your unused phones as webcams and use its on-board processor to detect features like people, pets, loud noises, and also recognise known faces and more.

A native Android security-cam app (Kotlin + Jetpack Compose, package `io.securitycam.level2`) that turns a phone into a "security camera": it monitors the camera and microphone for motion events and alerts the user through pluggable notification channels (Telegram, email, Pushover, webhook). Each channel has its own user-defined settings, and each detector
type can be individually enabled, tuned, and routed to specific channels. Recording runs in
a foreground service so monitoring survives screen-off.

Each release includes an apk for installation on your device.

## Getting started

Requires JDK 17 (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` here) and an Android SDK
(`ANDROID_HOME=/home/tpa/code/android-env/android-sdk`):

```sh
cd android
ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:testDebugUnitTest   # JVM suite
ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:assembleDebug       # debug APK
```

On-device integration tests need an AOSP emulator (see `AGENTS.md` for emulator discipline).

## License

AGPL-3.0 (see `LICENSE`). The app bundles YOLO-family / YAMNet-class models which are
AGPL-3.0-compatible.
