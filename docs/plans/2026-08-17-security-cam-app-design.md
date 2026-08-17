# Security Camera App — Design

Date: 2026-08-17
Status: Complete — Sections 1–6 approved and written (design validated; implementation pending)

## Goal

A mobile app for Android + iOS that turns a phone into a "security camera": it monitors the
camera feed for movement and alerts the user through pluggable notification **channels**
(email, Telegram, more later). Each channel has its own user-defined settings.

## Locked decisions (from Q&A)

| Topic | Decision |
|---|---|
| Monitoring behavior | Android: background monitoring via foreground service (screen off / locked OK). iOS: foreground-only, auto-lock suppressed; stops if manually locked |
| Tech stack | Flutter prototype → future port to native Kotlin/Swift (KMP) once features are locked. Portability comes from clean contracts, not language |
| Detection v1 | Pixel-diff motion detection with a sensitivity setting |
| Detection roadmap | Motion → person detection → pose detection; posture/fall detection becomes available downstream of pose |
| Alert payload | Text + snapshot image (frame captured on motion event) |
| Channel lineup v1 | Email (direct SMTP from device), Telegram (bot token + chat ID), Discord (webhook URL). Each channel has user-defined settings |
| Local notifications | **Excluded** — the app is a remote camera on an unsupervised device; alerts must reach a *different* device, not the phone doing the monitoring |
| Licensing | App released open-source under AGPL-3.0 → AGPL YOLO-family models are compatible; app store publication OK |

## Architecture (Section 1 — approved)

```
┌────────────────────────── Flutter app ──────────────────────────┐
│  UI layer                                                        │
│   Monitor screen │ Channel settings │ Event log │ Camera setup  │
├─────────────────────────────────────────────────────────────────┤
│  MonitorController (state machine: idle → starting → monitoring │
│  → paused → error)  ← single source of truth, drives everything │
├─────────────────────────────────────────────────────────────────┤
│  Detector pipeline (contract: Detector)                         │
│   MotionDetector (v1) → PersonDetector → PoseDetector (roadmap) │
│                          │ frames                                │
│  Camera session (camera plugin) ── live preview + downscaled    │
│                          │ stream for detection                 │
│  Event pipeline: motion event → snapshot → throttle/cooldown →  │
│                          │ dispatch to channels                 │
│  Channels (contract: Channel)                                   │
│   EmailChannel (SMTP) │ TelegramChannel │ registry for more     │
├─────────────────────────────────────────────────────────────────┤
│  Storage: settings │ secure credentials │ event log │ snapshots  │
└─────────────────────────────────────────────────────────────────┘
```

### Monitoring lifecycle

- **Android** — foreground service (`flutter_foreground_task` 10.x) keeps process + camera
  alive with screen off or phone locked. Persistent non-dismissible notification
  ("Monitoring active"), tap opens app. Declares `camera` foreground-service type (required
  Android 14+, enforced through Android 16): manifest permissions
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, runtime `CAMERA` +
  `POST_NOTIFICATIONS`; `<service android:foregroundServiceType="camera">`.
  - **No time limit** on `camera` FGS (the 6h/24h `onTimeout()` caps apply only to
    `dataSync`/`mediaProcessing`).
  - Must be started from a visible Activity / user action — never from `BOOT_COMPLETED`
    (blocked for `camera` since Android 15). Screen can go off immediately after start.
  - Screen-off capture needs `PARTIAL_WAKE_LOCK` (`ForegroundTaskOptions(allowWakeLock:
    true)`); a bare wake lock without an FGS is revoked in Doze. Plan for OEM battery-killer
    overrides (per-OEM "unrestricted battery" prompt in-app).
  - **Main-isolate note:** run camera + motion detection in the main isolate; the FGS only
    keeps the process alive. `flutter_foreground_task`'s background-Dart-isolate has a known
    degradation issue with Flutter 3.29+ merged-platform-UI-thread changes — avoid it.
  - Known caveat (v10): `flutter_foreground_task` requires Kotlin 1.9.10+, Gradle 8.6+,
    minSdk 23.
- **iOS** — keep-awake via `wakelock_plus` (1.7.x; `isIdleTimerDisabled`) while monitoring;
  lifecycle observer stops monitoring cleanly on backgrounding / manual lock.
  - Camera capture while backgrounded remains prohibited (iOS 26 unchanged); the VoIP/PiP
    multitasking-camera trick is for call apps and risks App Store rejection — not used.
  - **Re-assert keep-awake on `willEnterForeground`** — Low Power / Adaptive Power Mode can
    override the idle-timer disable; an active capture session also signals the system to
    stay awake.
  - `wakelock_plus` 1.7.x requires Flutter 3.41+/Dart 3.11+, min iOS 13.
- Camera stream feeds both the live preview and a downscaled analysis stream
  (e.g. 160×120 grayscale, ~2–4 fps during monitoring) to keep CPU/battery sane.
- Toolchain floor for the project: Flutter ≥ 3.41 / Dart ≥ 3.11 (satisfied by any current
  stable, e.g. Flutter 3.47 / Dart 3.13).

### Key isolation rules (make the KMP port cheap)

- `Detector`, `Channel`, and the event pipeline are abstract contracts with no Flutter
  dependencies — pure Dart interfaces + data types.
- The camera session is the only place touching platform-specific camera APIs.
- Everything else is plain Dart logic that a Kotlin/Swift rewrite reimplements against the
  same contracts.

## Detection pipeline (Section 2 — approved)

### The detector contract (pure Dart, zero Flutter/platform dependencies)

```dart
abstract class Detector {
  String get id;
  Future<void> init();                    // load model sessions etc.
  DetectionResult analyze(Frame frame);   // may use internal state
  void reset();                           // on monitoring start (clear prev frame, counters)
  Future<void> dispose();                 // release model sessions
}

class DetectionResult {
  final DateTime timestamp;
  final double score;          // 0..1, how strong the signal is
  final bool triggered;        // detector-specific decision
  final List<Detection> detections; // empty in v1; person boxes, pose keypoints later
}

class Frame {
  final DateTime timestamp;
  final GrayscaleBitmap bitmap; // small, e.g. 160×120
}
```

### Detectors are stateful, but self-contained

- Each detector is a **self-contained stateful unit**: it owns its internal state
  (e.g. MotionDetector holds the previous frame + debounce counter; PersonDetector holds its
  loaded TFLite interpreter session).
- **State is private to the detector** — never shared or passed between detectors.
- **Lifecycle is owned by the pipeline**: `init()` once when monitoring starts (a YOLO
  session loads once, reused across thousands of frames), `reset()` per monitoring session
  (prevents stale state — e.g. a ghost frame from last night triggering today's alert),
  `dispose()` when monitoring stops.
- Stateless part is the *composition*: detectors remain chainable and replaceable; the
  pipeline never assumes anything about a detector's internals.

### MotionDetector (v1) — algorithm

1. Camera session feeds downscaled grayscale frames (160×120, ~4 fps) to the pipeline.
2. Compares each frame to the previous: per-pixel absolute difference → pixels exceeding a
   diff threshold → ratio of changed pixels = motion score (0..1).
3. **Sensitivity setting** (user slider) maps inversely to the score threshold: high
   sensitivity → low threshold → small movements trigger.
4. **Debounce**: motion must persist for N consecutive frames (default 2–3) before
   `triggered = true`, killing single-frame flicker/noise.
5. Pipeline-level **cooldown** (user-configurable, default 60 s) suppresses repeated alerts
   after the first.

### Alert gating, by phase

- **v1**: motion `triggered` → capture snapshot → dispatch to channels.
- **Phase 2 (person)**: detectors chain — `MotionDetector` gates the expensive stage: only
  on motion does `PersonDetector` run on that frame; alert only if a person is found. Keeps
  battery sane. Model: **YOLO26n** (current Ultralytics lineup, v8.4.x; YOLO11 is the stable
  fallback; YOLO12/13 not for production), exported via **LiteRT** (`model.export(format:
  "litert")`, dynamic-INT8 `w8a32` keeps the NMS-free end-to-end branch), run with
  `tflite_flutter` ≥ **0.12.1** (LiteRT 1.4.0 runtime; Android 16KB page-size compliance
  required by Google Play). AGPL-3.0 — compatible with our AGPL-3.0 app.
- **Phase 3 (pose)**: `PoseDetector` downstream of person; posture/fall detection becomes a
  consumer of pose keypoints — same contract, new stages. Model: **YOLO26n-pose** (person
  boxes + 17 keypoints in one graph), again via LiteRT + `tflite_flutter`; MoveNet remains a
  legacy fallback. (Official MediaPipe Tasks Flutter vision support does not exist — do not
  plan around it.)

Pipeline-level concerns (throttling, snapshot, delivery) live in the event pipeline, never
in detectors.

## Channels (Section 3 — approved)

### The channel contract (pure Dart, no Flutter deps)

```dart
abstract class Channel {
  String get id;                     // unique per configured channel instance
  String get type;                   // 'email' | 'telegram' | ...
  ChannelSettings get settings;      // user-defined per-channel settings
  Future<void> send(AlertMessage msg);      // throws on delivery failure
  Future<void> sendTest();                   // fires a test alert (UI "Test" button)
  String? validate();                // null if settings OK, else human-readable problem
}

class AlertMessage {
  final DateTime timestamp;
  final String text;                 // e.g. "Motion detected in hallway at 14:32"
  final Uint8List? snapshotJpeg;     // optional attachment
  final String? snapshotName;
}

abstract class ChannelSettings {
  String get type;
  Map<String, dynamic> toJson();
  List<String> get secretFields;     // which fields go to secure storage
}
```

### Per-channel settings + validation

| Channel | Settings | Validation |
|---|---|---|
| **email** | smtpHost, smtpPort, tlsMode (none/STARTTLS/TLS), username, password, fromAddress, toAddresses | host non-empty, port 1–65535, fromAddress valid, ≥1 to address, password required if username set |
| **telegram** | botToken, chatId | both non-empty; token format `\d+:[A-Za-z0-9_-]+` |
| **discord** | webhookUrl | valid URL of form `https://discord.com/api/webhooks/<id>/<token>` |

### Delivery

- **Email** — SMTP via the `mailer` package (v7.x, community-maintained): MIME message (text
  body + JPEG attachment), TLS per tlsMode. Ports: 587 STARTTLS / 465 implicit SSL (port 25
  is carrier-blocked — avoid). Auth caveat (2026): **app passwords work for personal Gmail**
  (requires 2-Step Verification); **Google Workspace requires OAuth 2.0 / XOAUTH2** since May
  2025 — the SMTP channel should support both PLAIN and XOAUTH2 auth, and note the Workspace
  limitation in the UI. Other SMTP providers (Zoho, Fastmail, self-hosted) work with
  username/password.
- **Telegram** — `sendPhoto` (caption carries the text) to
  `api.telegram.org/bot<token>/sendPhoto`; if the image upload fails, fall back to
  `sendMessage` with text only so the alert is never lost to a media failure. (API 10.x,
  photo limit 10 MB — unchanged.)
- **Discord** — POST to the webhook URL as multipart/form-data: `content` = alert text,
  `file` = snapshot JPEG (webhook file limit **20 MB**). If the attachment fails, fall back to a
  content-only message. The webhook URL is a bearer secret — treated as a credential, stored
  in secure storage; if leaked, the fix is regenerating the webhook in Discord. Messages
  land in the (typically private) server/channel the user created the webhook in.

### WebhookChannel base (future-proofing)

Discord, Slack, Teams, ntfy.sh, and custom webhooks are all the same shape: POST to a URL
with optional bearer secret. Planned as **one channel type with presets** — `WebhookChannel`
base class + a form template per preset (discord, slack, teams, ntfy, custom). Phase 2 adds
the non-Discord presets without new classes.

### Registry (pluggability)

One factory map — `{'email': EmailChannel.new, 'telegram': TelegramChannel.new,
'discord': DiscordChannel.new}`. Adding a future channel = a new `*Channel` class + settings
class + one registry entry + a UI form. No pipeline changes.

### Credentials

Settings stored as JSON; fields listed in `secretFields` (SMTP password, bot token, Discord
webhook URL — the whole URL is a bearer token) go to `flutter_secure_storage` **≥ 10.x**
(v10 rewrite uses RSA-OAEP-SHA256 + AES-GCM instead of the deprecated
`encryptedSharedPreferences`; enable `migrateWithBackup`, verify v9→v10 migration on a real
device; iOS Keychain data is wiped on uninstall unless keychain-access-groups are
configured). The rest goes to `shared_preferences`, keyed by channel id.

### Delivery pipeline

On alert → snapshot → each enabled channel `send()`s → retry with backoff (3 attempts) on
failure → the event log records per-channel status (pending/delivered/failed).

## Data & UI (Section 4 — approved)

### Screens

1. **Monitor screen** (primary) — live camera preview, camera picker (front/back + any
   additional physical cameras), user-assignable **camera name** (e.g. "Hallway") used in
   alert text and event log entries, sensitivity slider, big Start/Stop button, status
   indicator (monitoring / paused / error, source: `MonitorController` state machine).
2. **Channels screen** — list of configured channels with enable toggles; add/edit forms per
   channel type (one form per type, driven by the registry); "Test" button per channel;
   delete.
3. **Event log** — chronological list: thumbnail, timestamp, camera name, motion score,
   per-channel delivery status (pending / delivered / failed); tap for full snapshot view.
4. **Settings** — cooldown seconds, debounce frames, snapshot retention; future: alert text
   template.

### Storage

| Data | Where |
|---|---|
| Non-secret settings (camera id, camera name, sensitivity, cooldown, channel plain fields) | `shared_preferences` |
| Secrets (SMTP password, bot token) | `flutter_secure_storage` (Keychain/Keystore) |
| Event log | SQLite via `sqflite` (testable on desktop with `sqflite_common_ffi`) — id, timestamp, camera name, score, snapshot path, per-channel statuses |
| Snapshot JPEGs | App documents dir: `snapshots/2026-08-17T14-32-05.jpg`; retention caps count (default 200) with cleanup on app start |

Alert text uses the camera name: "Motion detected in Hallway at 14:32".

### UI plumbing

Minimal — `MonitorController` exposes `ChangeNotifier`, screens use built-in
`ListenableBuilder`/`ValueListenableBuilder`. No heavy state-management dependency in the
prototype; if it grows, introduce one deliberately rather than up front.

## Error handling & testing (Section 5 — approved)

### Error handling

1. **Channel delivery failures** — retry with backoff (3 attempts), then mark the event's
   per-channel status as `failed`; the snapshot is never deleted on failure, so the event
   remains inspectable. Failures surface in the event log and on the channels screen (last
   test/send result per channel).
2. **Permission flows** — camera denied → guided explanation + deep link to system settings;
   Android 13+ notification permission requested when monitoring starts; first-run onboarding
   checks all required permissions up front.
3. **Camera errors** — camera in use by another app (wait/retry), no cameras available
   (clear error state), session dropped mid-monitoring (attempt reopen; if it fails, stop
   monitoring cleanly and notify via the persistent notification).
4. **Lifecycle errors** — foreground-service start failure on Android, keep-awake failure on
   iOS → both stop monitoring and surface an error state in `MonitorController`.
5. **Configuration errors** — `validate()` runs before every send and on form save; invalid
   channels never enter the delivery path.
6. **Offline** — SMTP/Telegram/Discord timeouts → retry → fail; the event is still logged
   with its snapshot, so nothing is lost even when the phone is offline.

### Testing

| Layer | Approach |
|---|---|
| MotionDetector | Unit tests on synthetic frame sequences: no change → no trigger; threshold crossing; sensitivity mapping; debounce (N frames); `reset()` clears state |
| Pipeline | Cooldown/throttle behavior, alert-text template (camera name) |
| Channels | Email: in-process mock SMTP server; Telegram/Discord: local mock HTTP server asserting request shape + image-failure fallbacks |
| Settings | JSON round-trip, `secretFields` separation, validation rules per channel |
| Widgets | Monitor screen states (idle/monitoring/error), channel form validation, event log rendering |
| Storage | SQLite via `sqflite_common_ffi` on desktop (integration tests) |
| Device matrix | Android: screen-off foreground service, camera front/back, min-supported API; iOS: foreground monitoring, manual-lock stop, wakelock |

Plus a manual checklist for real-world verification: Gmail app-password SMTP, a real
Telegram bot, a real Discord webhook, and a real phone on each platform.

## Roadmap phasing (Section 6 — approved)

| Phase | Scope |
|---|---|
| **0 — Core prototype** | Flutter scaffold, camera session + live preview, MotionDetector + sensitivity, event pipeline (snapshot + cooldown), camera name, Telegram channel, event log |
| **1 — Alert completeness** | Email (SMTP) + Discord channels + channel settings UI, Android foreground service (screen-off monitoring), iOS keep-awake + lifecycle stop |
| **2 — Webhook family + person detection** | `WebhookChannel` presets (ntfy / Slack / Teams / custom), Pushover channel, person detection (YOLO26n via LiteRT + `tflite_flutter`, gated by motion) |
| **3 — Pose + IoT** | MQTT channel (Home Assistant/IoT), pose detection (YOLO26n-pose), posture/fall downstream of pose keypoints; evaluate KMP port |
| **Later (noted, not planned)** | WhatsApp/Signal/iMessage (no consumer API), SMS (requires paid gateway), local notifications (excluded — remote unsupervised device) |

## Appendix A — 2026 validation (external research, Aug 17 2026)

Design assumptions re-verified against current docs/package registries. No changes to the
architecture were required.

| Design element | Verdict | Key update folded into this doc |
|---|---|---|
| Flutter + Dart, zero-dep state | ✅ KEEP | Flutter 3.47 / Dart 3.13; no Flutter 4.x; `ChangeNotifier` + `ListenableBuilder` still idiomatic |
| `camera` plugin | ✅ KEEP | 0.12.x, CameraX backend; background streaming needs `FOREGROUND_SERVICE_CAMERA` |
| `tflite_flutter` | ✅ KEEP | ≥ 0.12.1 (LiteRT 1.4.0; Android 16KB page-size compliance for Play); `tflite_flutter_helper` deprecated — do pre/post-processing manually |
| Person detection model | 🔄 UPDATE | YOLO26n current (Jan 2026, v8.4.x); YOLO11 fallback; YOLO12/13 not for production; export `format="litert"`; AGPL-3.0 confirmed (compatible with our AGPL-3.0 app; Apache-2.0 alternatives if ever closed: YOLOX-S / NanoDet / RTMDet) |
| Pose model | 🔄 UPDATE | YOLO26n-pose preferred (boxes + keypoints, one graph); MoveNet legacy; official MediaPipe Tasks Flutter vision does not exist |
| Android FGS monitoring | ✅ KEEP | `camera` FGS + `PARTIAL_WAKE_LOCK` still the documented screen-off pattern; no time limit on camera FGS; must start from visible activity (no `BOOT_COMPLETED`); Android 16 quotas affect only FGS-spawned background jobs |
| `flutter_foreground_task` | ✅ KEEP (caveat) | v10.0.0; minSdk 23; background-Dart-isolate degradation with Flutter 3.29+ → main-isolate design (this doc) |
| iOS foreground-only | ✅ KEEP | Camera-in-background still prohibited (iOS 26); `wakelock_plus` 1.7.x (Flutter 3.41+, iOS 13+); re-assert keep-awake on `willEnterForeground`; VoIP/PiP trick not used |
| Permissions | ✅ KEEP | No partial camera access on Android 15/16; `ACCESS_LOCAL_NETWORK` only if LAN streaming (future); `POST_NOTIFICATIONS` Android 13+ |
| `flutter_secure_storage` | ✅ KEEP | ≥ 10.x (v10 rewrite, cipher-based; verify migration on device) |
| Telegram / Discord / ntfy / Pushover | ✅ KEEP | APIs unchanged; Discord webhook file limit now 20 MB |
| Email via SMTP | ✅ KEEP (caveat) | `mailer` v7.x maintained; Google Workspace needs XOAUTH2 (May 2025+); app passwords personal-Gmail-only; ports 587/465 OK, 25 blocked |
| SQLite `sqflite` | ✅ KEEP (for now) | drift is the 2026 default, but `sqflite` + `sqflite_common_ffi` fine for a single event-log table; revisit at KMP port |