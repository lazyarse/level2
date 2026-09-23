# Security Cam (level2)

We believe everyone is entitled to feel safe. This is a free security camera application with advanced features to help you set up your unused phones as webcams and use its on-board processor to detect features like people, pets, loud noises, and also recognise known faces and more.

A native Android security-cam app (Kotlin + Jetpack Compose, package `io.securitycam.level2`) that turns a phone into a "security camera": it monitors the camera and microphone for motion events and alerts the user through pluggable notification channels (Telegram, email, Pushover, webhook). Each channel has its own user-defined settings, and each detector
type can be individually enabled, tuned, and routed to specific channels. Recording runs in
a foreground service so monitoring survives screen-off.

Each release includes an apk for installation on your device.

## Getting started

Requires JDK 17 (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` here) and an Android SDK
(`ANDROID_HOME=$HOME/android-sdk`):

```sh
cd android
ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:testFullDebugUnitTest :app:testFdroidDebugUnitTest   # JVM suite (both flavors)
ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:assembleFullDebug       # debug APK
ANDROID_HOME=... JAVA_HOME=... ./gradlew :app:assembleFdroidRelease    # F-Droid flavor (no face-recognition weights/UI)
```

On-device integration tests need an AOSP emulator (see `AGENTS.md` for emulator discipline).

## Notifications

Alerts are delivered through pluggable channels, each configured under
**Settings → Channels** in the app. Multiple accounts per type are supported,
and a **Test** button fires a trial alert.

- **Email** — the app speaks SMTP directly. Enter your provider's **SMTP host**,
  **port** (587 for STARTTLS, 465 for implicit TLS), **username**, **password**
  (an app-specific password if your provider requires one, e.g. Gmail), and the
  **from**/**to** addresses.
- **Telegram** — create a bot with [@BotFather](https://t.me/BotFather) to get a
  **bot token** (see the official
  [bot tutorial](https://core.telegram.org/bots/tutorial)). Message your bot once
  (press **Start** — a bot cannot initiate a conversation, which Telegram rejects
  with 403). Then find its **chat ID** from
  `curl "https://api.telegram.org/bot<TOKEN>/getUpdates"` → copy the `chat.id`
  from the latest update (a private group's ID works too — add the bot and post
  once). Alerts arrive as photos with a caption.
- **Pushover** — create an application on pushover.net to get an **app token**;
  your **user key** is on your dashboard. Optionally set a **sound**, **priority**
  (-2…2) and emergency re-alert retry/expiry seconds.
- **Webhook** — pick a **preset** (Discord, ntfy, Slack, Teams, custom), paste the
  **webhook URL** (https only), and optionally a **bearer token**. Custom hooks
  post JSON or plain text; the ntfy preset also takes a **title**.

## Video clips

Each motion event saves one clip to `Movies/level2` (MediaStore, fallback
`filesDir/videos/`). The app pre-buffers 5 s (`pre-roll`) and tails 5 s
(`post-roll`, `Settings → Video clips`) around the trigger; triggers within
15 s merge into one event, hard-capped at 120 s for perpetual motion.

**Keep monitoring for at least ~5 s after the last trigger.** Technically a
clip is valid once the post-roll is ≥1 s old (`POST_MIN_STOP_AGE_MS`,
covering the ~240 ms first-keyframe window); earlier stops still yield a clip
from the 5 s pre-roll buffer *in steady state* (monitoring ran >5 s before the
trigger). Stopping within the first second on a freshly started session can
leave the event with no video.

| Stop Δt | Result |
|---|---|
| ~0.5 s | `postRollIsMature()` false → tail runs to 5 s limit — valid (pre + tail) |
| 1 s | Boundary — clean cut past keyframe — valid |
| 4 s | Clean cut; late `onVideoReady` links ~8 s later — valid, play button appears late |
| 5 s | Full post-roll — valid |
| 15 s window | Fast-notify emits `video=null` immediately, clip linked ~8 s later |

For a continuous wave the event stays open until 15 s of quiet or the 120 s cap,
then the clip finalizes — expect up to ~2 m8 s to the play button in that case.

## License

SPDX: `AGPL-3.0-or-later` (see `LICENSE`). On-device model provenance lives
beside each binary (`android/app/src/main/assets/*.license` +
`*.source`); the F-Droid flavor additionally excludes face recognition
(`assembleFdroidRelease`, no `mobilefacenet.tflite`, UI hidden).
