# Security Cam

A Flutter mobile app (Android + iOS) that turns a phone into a "security camera": it
monitors the camera (and microphone) for motion / sound events and alerts the user through
pluggable channels (Telegram, email, Discord, ...). Each channel has its own user-defined
settings, and each detector type can be individually enabled, tuned, and routed to specific
channels.

**Design/plan:** `docs/plans/2026-08-17-security-cam-app-design.md` (upstream of this
directory).

## Development model

Desktop-first: the app is developed and smoke-tested on **Linux desktop** using simulated
camera/audio sessions, so the pure-Dart core (detectors, pipeline, channels, storage) is
exercised without a device. Real platform implementations slot in behind the same contracts:

- `CameraSession` — Android: native Kotlin CameraX module (screen-off FGS); iOS: `camera`
  plugin adapter; desktop: simulated feed.
- `Channel` / `Detector` / event pipeline — pure Dart, platform-independent.

## Getting started

```sh
flutter create --platforms=linux,android,ios --org io.securitycam --project-name security_cam security_cam
cd security_cam
flutter pub get
flutter test
flutter run -d linux
```

## License

AGPL-3.0 (see `LICENSE`). The app bundles YOLO-family / YAMNet-class models which are
AGPL-3.0-compatible.
