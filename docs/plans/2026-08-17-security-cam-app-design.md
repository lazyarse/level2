# Security Camera App — Design

Date: 2026-08-17
Status: Complete — Sections 1–6 approved; revised after cohesion review (Aug 17 2026): Android camera → native Kotlin module, audio monitoring added to prototype, audio split into per-type detectors with per-type channel routing

## Goal

A mobile app for Android + iOS that turns a phone into a "security camera": it monitors the
camera feed (and optionally the microphone) for movement and sound events and alerts the
user through pluggable notification **channels** (email, Telegram, more later). Each channel
has its own user-defined settings.

## Locked decisions (from Q&A)

| Topic | Decision |
|---|---|
| Monitoring behavior | Android: background monitoring via a native Kotlin foreground service (screen off / locked OK). iOS: foreground-only, auto-lock suppressed; stops if manually locked |
| Android camera (cohesion decision) | **Native Kotlin module** (`camera_service` plugin): a `LifecycleService` owning CameraX, acting as the FGS and attached to a FlutterEngine — required because the stock `camera` plugin stops streaming when the Activity stops (screen off). Replaces `flutter_foreground_task`. This module is the future KMP camera implementation |
| iOS camera | Stock `camera` plugin (camera_avfoundation) behind the same pure-Dart `CameraSession` contract; foreground-only |
| Detection v1 | Pixel-diff motion detection (camera) + audio-event detection (mic) |
| Audio monitoring (cohesion decision) | **In the prototype**: YAMNet int8 (baby cry + glass breaking) via `tflite_flutter`; audio is an independent trigger source in the event pipeline |
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
│  Detector pipeline (contract: Detector, multi-trigger)           │
│   MotionDetector (v1) │ BabyCryDetector │ GlassBreakDetector (v1,   │
│   shared YAMNet) │ → PersonDetector → PoseDetector (roadmap)        │
│                          │ frames / PCM                          │
│  Camera session (contract: CameraSession)                        │
│   Android: native Kotlin CameraX LifecycleService (FGS)          │
│   iOS: stock camera plugin (foreground)                         │
│                          │ any detector triggered                │
│  Event pipeline: trigger → snapshot → throttle/cooldown →       │
│                          │ dispatch to channels                 │
│  Channels (contract: Channel)                                   │
│   EmailChannel (SMTP) │ TelegramChannel │ registry for more     │
├─────────────────────────────────────────────────────────────────┤
│  Storage: settings │ secure credentials │ event log │ snapshots  │
└─────────────────────────────────────────────────────────────────┘
```

### Monitoring lifecycle

- **Android — native Kotlin `camera_service` module.** A `LifecycleService` owns the CameraX
  session (Preview + ImageAnalysis + ImageCapture), is the foreground service, and hosts a
  FlutterEngine so Dart keeps running with the screen off. This is the **only** supported way
  to stream camera frames screen-off: the stock `camera` plugin binds CameraX to the Activity
  lifecycle, so the stream dies when the Activity stops — `flutter_foreground_task` is **not
  used** (it only keeps the process alive, not the camera; its background-Dart-isolate also
  has a known degradation with Flutter 3.29+).
  - FGS declared `camera|microphone` (combined types), permissions
    `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`,
    runtime `CAMERA` + `RECORD_AUDIO` + `POST_NOTIFICATIONS`.
  - **No time limit** on camera/microphone FGS (the 6h/24h `onTimeout()` caps apply only to
    `dataSync`/`mediaProcessing`).
  - Must be started from a visible Activity / user action — never from `BOOT_COMPLETED`
    (blocked for camera since Android 15).
  - Screen-off needs `PARTIAL_WAKE_LOCK`; a bare wake lock without an FGS is revoked in Doze.
    Plan for OEM battery-killer overrides (per-OEM "unrestricted battery" prompt in-app).
  - Swipe-from-recents: because the service owns the camera lifecycle (not the Activity),
    monitoring survives; the service keeps running independently.
  - Privacy indicators (camera + mic pills, Android 12+) show while in use; the persistent
    non-dismissible notification ("Monitoring active") is mandatory.
- **iOS — stock `camera` plugin, foreground-only.** Keep-awake via `wakelock_plus` (1.7.x;
  `isIdleTimerDisabled`) while monitoring; lifecycle observer stops monitoring cleanly on
  backgrounding / manual lock.
  - Camera capture while backgrounded remains prohibited (iOS 26 unchanged); the VoIP/PiP
    multitasking-camera trick is for call apps and risks App Store rejection — not used.
  - **Re-assert keep-awake on `willEnterForeground`** — Low Power / Adaptive Power Mode can
    override the idle-timer disable; an active capture session also signals the system to
    stay awake.
  - `wakelock_plus` 1.7.x requires Flutter 3.41+/Dart 3.11+, min iOS 13.
- Camera stream feeds both the live preview and a downscaled analysis stream
  (e.g. 160×120 grayscale, ~2–4 fps during monitoring). The analysis stream is bounded
  natively (low/medium preset + capped fps) so the per-frame channel copy stays trivial.
- Microphone stream: 16 kHz mono PCM (~1 s windows) only during monitoring, classified
  on-device, never uploaded.
- Toolchain floor for the project: Flutter ≥ 3.41 / Dart ≥ 3.11 (satisfied by any current
  stable, e.g. Flutter 3.47 / Dart 3.13).

### CameraSession contract (pure Dart)

```dart
abstract class CameraSession {
  Future<void> init(CameraConfig config);        // camera id, analysis size, fps
  Stream<AnalysisFrame> get analysisFrames;      // 160×120 grayscale @ 2–4 fps
  Stream<PreviewFrame> get previewFrames;        // or native preview surface handle
  Future<Snapshot> takeSnapshot();               // native full-res JPEG (orientation handled)
  Future<void> dispose();
}
```

- **Android implementation**: the native `camera_service` module (CameraX in the
  LifecycleService; analysis frames + stills cross to Dart via EventChannel/MethodChannel).
- **iOS implementation**: an adapter over the stock `camera` plugin.
- One contract, two platform implementations — the clean seam the KMP port reuses as-is.

### Key isolation rules (make the KMP port cheap)

- `Detector`, `Channel`, and the event pipeline are abstract contracts with no Flutter
  dependencies — pure Dart interfaces + data types.
- `CameraSession` is the only platform-dependent contract; it has exactly two
  implementations (native Kotlin module on Android, stock `camera` plugin on iOS) behind a
  single Dart interface.
- Everything else is plain Dart logic that a Kotlin/Swift rewrite reimplements against the
  same contracts.

## Detection pipeline (Section 2 — approved)

### The detector contract (pure Dart, zero Flutter/platform dependencies)

```dart
class DetectorConfig {
  final String type;                // 'motion' | 'baby_cry' | 'glass_break' | ...
  final bool enabled;
  final double threshold;           // sensitivity mapping; per-type
  final int persistenceFrames;      // windows of persistence (2-3 typical)
  final Duration cooldown;          // per-detector (default 60 s)
  final List<String> routeToChannelIds; // which channels get this type's alerts
}

abstract class Detector {
  String get id;                    // = config.type
  DetectorConfig get config;
  String get triggerType;           // labels the alert, e.g. 'motion' | 'baby_cry'
  Future<void> init();              // load model sessions etc.
  DetectionResult analyze(Frame frame);   // may use internal state
  void reset();                     // on monitoring start (clear prev frame, counters)
  Future<void> dispose();           // release model sessions
}

class DetectionResult {
  final DateTime timestamp;
  final String triggerType;
  final double score;          // 0..1, how strong the signal is
  final bool triggered;        // detector-specific decision
  final List<Detection> detections; // empty in v1; person boxes, pose keypoints later
}

class Frame {
  final DateTime timestamp;
  final GrayscaleBitmap bitmap; // small, e.g. 160×120
}

class AudioFrame {
  final DateTime timestamp;
  final Float32List pcm16;      // 0.975 s window, mono, 16 kHz, values in [-1, 1]
}
```

### Detector registry & configuration

- Detectors are **configured instances**, not hardcoded: a registry maps
  `{'motion': MotionDetector.new, 'baby_cry': BabyCryDetector.new, 'glass_break':
  GlassBreakDetector.new, ...}`. The pipeline instantiates the enabled detectors from stored
  `DetectorConfig`s on monitoring start.
- Each type is **independently toggled, tuned, and cooled down**, and carries its own
  `routeToChannelIds` (delivery routing — Section 3). Adding a future type (e.g. `scream`,
  `gunshot`, `person`) = a detector class + one registry entry + a config/UI row.

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
5. **Per-detector cooldown** (user-configurable, default 60 s) suppresses repeated alerts of
   this type after the first; a global cap to prevent alert storms may be added later.
6. Grayscale conversion samples the **Y plane directly** (Android) / luma from BGRA (iOS) —
   the `image` package is not used for per-frame work.

### Audio detectors (v1) — per-type instances

1. Mic stream (16 kHz mono PCM, `record ^5`) accumulates into ~1 s windows (0.975 s = 15600
   samples, matching YAMNet's input) and is fed to the pipeline once per window.
2. **YAMNet int8** (`tfhub.dev/google/lite-model/yamnet/classification/tflite/1`,
   Apache-2.0, ~400 KB) via `tflite_flutter` — ~12 ms per window (~1% duty cycle), run once
   per window in the worker isolate; optional RMS/energy pre-gate to skip silence.
3. **Shared inference, per-type decision**: one YAMNet run emits scores for all 521 AudioSet
   classes; each audio detector (`baby_cry`, `glass_break`, later `scream`, `gunshot`, ...)
   reads that same score vector and applies its own class selection, threshold, persistence,
   and cooldown. Cost stays ~1% duty cycle no matter how many audio types are enabled.
4. **Baby cry** (`BabyCryDetector`) = class 20; trigger threshold ~0.5 with 2-of-3 window
   persistence (median confidence ~0.83 — usable as an alert trigger).
5. **Glass break** (`GlassBreakDetector`) = fuse classes 435 `Glass` / 437 `Shatter` /
   463 `Smash, crash` / 464 `Breaking` (max), threshold ~0.5 with persistence —
   proof-of-concept quality (median ~0.5, expect false alarms from TV/speakers/slams).
6. Scores are uncalibrated — thresholds are per-site settings, not constants.

### Multi-trigger event pipeline

- The pipeline fires on **any detector's `triggered`** (motion, baby cry, glass break — later
  person), each with its own sensitivity, persistence, and **per-detector cooldown**.
- The alert text names the trigger type: "Motion detected in Hallway at 14:32" /
  "Baby crying detected in Hallway at 14:32" / "Glass breaking detected in Hallway at
  14:32".
- **Delivery routing** happens at dispatch: an alert goes to the enabled channels **∩** that
  trigger type's `routeToChannelIds` (a type may route to all, a subset, or none = log-only).
- Camera chaining stays: **motion gates the expensive person/pose stages** (Phase 2+); audio
  is an independent source and does not gate on motion.

### Alert gating, by phase

- **v1**: any enabled detector `triggered` (motion, baby cry, glass break) → capture snapshot
  → route to that type's channels.
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
  final String triggerType;        // 'motion' | 'baby_cry' | 'glass_break' | ...
  final String text;               // e.g. "Baby crying detected in Hallway at 14:32"
  final Uint8List? snapshotJpeg;   // optional attachment
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

On any detector trigger → snapshot → route: enabled channels **∩** that trigger type's
`routeToChannelIds` → each routed channel `send()`s → retry with backoff (3 attempts) on
failure → the event log records per-channel status (pending/delivered/failed). A trigger
type routed to no channels is logged but not delivered. Snapshot, JPEG encoding, SQLite
writes, and channel HTTP run in a long-lived **worker isolate** so monitoring on the main
isolate never blocks on I/O.

## Data & UI (Section 4 — approved)

### Screens

1. **Monitor screen** (primary) — live camera preview, camera picker (front/back + any
   additional physical cameras), user-assignable **camera name** (e.g. "Hallway") used in
   alert text and event log entries, quick toggles for motion + audio, big Start/Stop
   button, status indicator (monitoring / paused / error, source: `MonitorController` state
   machine).
2. **Channels screen** — list of configured channels with enable toggles; add/edit forms per
   channel type (one form per type, driven by the registry); "Test" button per channel;
   delete.
3. **Event log** — chronological list: thumbnail, timestamp, camera name, **trigger type**
   (motion / baby cry / glass), score, per-channel delivery status (pending / delivered /
   failed); tap for full snapshot view.
4. **Settings → Detection** — a list of detector types (motion, baby cry, glass break; more
   later), each with an enable toggle, sensitivity slider, persistence, and a **"routes to"
   channel multi-select** (from configured channels; "none" = log-only).
5. **Settings** — snapshot retention; future: alert text template, global alert cap.

### Storage

| Data | Where |
|---|---|
| Non-secret settings (camera id, camera name, channel plain fields) | `shared_preferences` |
| Detector configs (type, enabled, threshold, persistence, cooldown, routeToChannelIds) | `shared_preferences` as JSON (registry-indexed) |
| Secrets (SMTP password, bot token) | `flutter_secure_storage` (Keychain/Keystore) |
| Event log | SQLite via `sqflite` (testable on desktop with `sqflite_common_ffi`) — id, timestamp, camera name, trigger type, score, snapshot path, per-channel statuses |
| Snapshot JPEGs | App documents dir: `snapshots/2026-08-17T14-32-05.jpg`; retention caps count (default 200) with cleanup on app start |

Alert text uses the camera name and trigger type: "Baby crying detected in Hallway at
14:32".

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
   mic denied (Android `RECORD_AUDIO`, iOS `NSMicrophoneUsageDescription`) handled the same
   way; Android 13+ notification permission requested when monitoring starts; first-run
   onboarding checks all required permissions (CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS) up
   front and explains why the mic FGS type + persistent notification are needed.
3. **Camera errors** — camera in use by another app (wait/retry), no cameras available
   (clear error state), session dropped mid-monitoring (attempt reopen; if it fails, stop
   monitoring cleanly and notify via the persistent notification).
4. **Audio errors** — iOS `AVAudioSession` conflict with the camera plugin (set category
   `.playAndRecord` + mode `.measurement` once; if the session is lost mid-monitoring, log
   the audio trigger offline and re-assert the category); mic already in use by another app
   on Android.
4. **Lifecycle errors** — foreground-service start failure on Android, keep-awake failure on
   iOS → both stop monitoring and surface an error state in `MonitorController`.
5. **Configuration errors** — `validate()` runs before every send and on form save; invalid
   channels never enter the delivery path.
6. **Offline** — SMTP/Telegram/Discord timeouts → retry → fail; the event is still logged
   with its snapshot, so nothing is lost even when the phone is offline.

### Testing

| Layer | Approach |
|---|---|
| MotionDetector | Unit tests on synthetic frame sequences (Y-plane sampling): no change → no trigger; threshold crossing; sensitivity mapping; debounce (N frames); per-type cooldown; `reset()` clears state |
| Audio detectors | Unit tests on synthetic PCM per type: silence → no trigger; class-20 (baby cry) above/below threshold; 2-of-3 persistence; fused glass-class max; a second type triggering independently of the first; `reset()` clears state |
| Pipeline / routing | Multi-trigger + per-detector cooldown; routing = enabled channels ∩ routeToChannelIds; type routed to no channels → logged, not delivered; alert-text template (camera name + trigger type) |
| Channels | Email: in-process mock SMTP server; Telegram/Discord: local mock HTTP server asserting request shape + image-failure fallbacks |
| Settings | JSON round-trip (channel + detector configs), `secretFields` separation, validation rules per channel and detector |
| Widgets | Monitor screen states (idle/monitoring/error), Detection settings rows (toggle/sensitivity/routes-to), channel form validation, event log rendering |
| Storage | SQLite via `sqflite_common_ffi` on desktop (integration tests) |
| Native module | Kotlin/CameraX unit + instrumented tests; screen-off stream delivery (emulator pixel_34: lock + verify frames still arrive); still capture; service lifecycle |
| Device matrix | Android: screen-off foreground service (camera+mic), camera front/back, min-supported API; iOS: foreground monitoring, manual-lock stop, wakelock, AVAudioSession co-existence |

Plus a manual checklist for real-world verification: Gmail app-password SMTP, a real
Telegram bot, a real Discord webhook, a real phone on each platform, and audio triggers
(play a real baby-cry/glass-break sample at the monitored phone; tune per-site thresholds).

## Roadmap phasing (Section 6 — approved)

| Phase | Scope |
|---|---|
| **0 — Core prototype (Android-first)** | Flutter scaffold, **native `camera_service` module** (CameraX LifecycleService, screen-off FGS, camera+mic), detector registry (MotionDetector + BabyCryDetector + GlassBreakDetector sharing one YAMNet inference), multi-trigger event pipeline (snapshot, per-detector cooldown, route-by-trigger-type, worker-isolate offload), camera name, Telegram channel, event log |
| **1 — Alert completeness** | Email (SMTP) + Discord channels + channel settings UI, iOS camera adapter + keep-awake + lifecycle stop (iOS is foreground-only) |
| **2 — Webhook family + person detection** | `WebhookChannel` presets (ntfy / Slack / Teams / custom), Pushover channel, person detection (YOLO26n via LiteRT + `tflite_flutter`, gated by motion) |
| **3 — Pose + IoT** | MQTT channel (Home Assistant/IoT), pose detection (YOLO26n-pose), posture/fall downstream of pose keypoints; evaluate KMP port (the Kotlin module is already the KMP camera implementation) |
| **Later (noted, not planned)** | WhatsApp/Signal/iMessage (no consumer API), SMS (requires paid gateway), local notifications (excluded — remote unsupervised device) |

## Appendix A — 2026 validation (external research, Aug 17 2026)

Design assumptions re-verified against current docs/package registries. Initial pass (A1)
checked components in isolation; a second **cohesion pass** (A2) checked how the pieces fit
together and produced the native-module + audio decisions.

### A1 — Component validation

| Design element | Verdict | Key update folded into this doc |
|---|---|---|
| Flutter + Dart, zero-dep state | ✅ KEEP | Flutter 3.47 / Dart 3.13; no Flutter 4.x; `ChangeNotifier` + `ListenableBuilder` still idiomatic |
| `tflite_flutter` | ✅ KEEP | ≥ 0.12.1 (LiteRT 1.4.0; Android 16KB page-size compliance for Play); `tflite_flutter_helper` deprecated — do pre/post-processing manually |
| Person detection model | 🔄 UPDATE | YOLO26n current (Jan 2026, v8.4.x); YOLO11 fallback; YOLO12/13 not for production; export `format="litert"`; AGPL-3.0 confirmed (compatible with our AGPL-3.0 app; Apache-2.0 alternatives if ever closed: YOLOX-S / NanoDet / RTMDet) |
| Pose model | 🔄 UPDATE | YOLO26n-pose preferred (boxes + keypoints, one graph); MoveNet legacy; official MediaPipe Tasks Flutter vision does not exist |
| Audio model | ✅ KEEP | **YAMNet int8** (Apache-2.0, ~400 KB, 0.975 s / 16 kHz mono, ~12 ms CPU) — baby-cry class 20 usable (med. conf 0.83); glass = fused Glass/Shatter/Smash/Breaking, proof-of-concept (med. ~0.5). PANNs CNN14 = heavy second-opinion only. **ESC-50 and Donate-a-Cry corpora are CC BY-NC → store blocker; avoid fine-tuning on them** |
| Audio capture | ✅ KEEP | `record ^5` (MIT) 16 kHz mono PCM stream; accumulate 1 s windows; energy pre-gate optional. Battery ≈ a few % over 8–12 h (vs camera 10–30%+) |
| iOS foreground-only | ✅ KEEP | Camera-in-background still prohibited (iOS 26); `wakelock_plus` 1.7.x (Flutter 3.41+, iOS 13+); re-assert keep-awake on `willEnterForeground`; VoIP/PiP trick not used |
| Permissions | ✅ KEEP | No partial camera access on Android 15/16; `ACCESS_LOCAL_NETWORK` only if LAN streaming (future); `POST_NOTIFICATIONS` Android 13+; mic adds `RECORD_AUDIO` + FGS `microphone` type |
| `flutter_secure_storage` | ✅ KEEP | ≥ 10.x (v10 rewrite, cipher-based; verify migration on device) |
| Telegram / Discord / ntfy / Pushover | ✅ KEEP | APIs unchanged; Discord webhook file limit now 20 MB |
| Email via SMTP | ✅ KEEP (caveat) | `mailer` v7.x maintained; Google Workspace needs XOAUTH2 (May 2025+); app passwords personal-Gmail-only; ports 587/465 OK, 25 blocked |
| SQLite `sqflite` | ✅ KEEP (for now) | drift is the 2026 default, but `sqflite` + `sqflite_common_ffi` fine for a single event-log table; revisit at KMP port |

### A2 — Cohesion findings (drove the two decisions)

| Cohesion point | Finding | Resolution in this doc |
|---|---|---|
| Concurrent still capture | ✅ `takePicture()` during `startImageStream` works on both platforms (camerax 0.7.1+ removed concurrency restrictions) | Snapshot via `takePicture()`; stream-frame JPEG only as fallback |
| Stream → grayscale cost | ✅ Cheap if done right | Sample the Y plane directly (skip `image` package); bound stream natively (low/medium preset + `MediaSettings.fps` 2–4) |
| **Screen-off monitoring on Android** | ❌ **Stock `camera` plugin cannot do it** — CameraX binds to the Activity lifecycle; screen off → Activity `ON_STOP` → stream dies. FGS keeps the process, not the camera | **Native Kotlin `LifecycleService` module (decision)** |
| `flutter_foreground_task` | ❌ Not used | Replaced by the native module (also avoids its background-Dart-isolate degradation + camera `MissingPluginException` on the task engine) |
| Main-isolate frame work | ✅ Fine at 160×120 @ 2–4 fps | Offload JPEG encode + SQLite + HTTP to a long-lived worker isolate (not `Isolate.run` per event) |
| Disk/network during monitoring | ✅ No Doze restriction on disk I/O; HTTP from an FGS = foreground network policy | WAL + batched writes; wakelock + battery exemption for deep Doze |
| Audio as trigger source | ✅ Cheap (~1% duty cycle), feasible both platforms (iOS foreground-only) | **Audio monitoring in the prototype (decision)**; multi-trigger pipeline |
| iOS mic + camera session | ⚠️ `AVAudioSession` conflict risk | Set `.playAndRecord` + `.measurement` once; re-assert on session loss |

## Appendix B — Dev environment (this machine, Aug 2026)

Working **Android** toolchain; **no iOS toolchain** (Linux host — building/running iOS requires a
Mac with Xcode; the project's `ios/` folder is still generated, iOS testing happens later on a
Mac).

| Component | State | Notes |
|---|---|---|
| Flutter | 3.41.2 stable / Dart 3.11.0 | Meets design floor (≥3.41); `flutter_foreground_task` is no longer used (replaced by the native module) |
| Android SDK | `/home/tpa/code/android-env/android-sdk` | platforms android-33/34/36, build-tools 34.0.0/35.0.0, NDK 28.2, licenses accepted; `flutter doctor` Android toolchain ✓ |
| JDK | 17 / 21 / 25 installed (`/usr/lib/jvm`) | PATH currently uses 25 — **set `flutter config --jdk-dir` to JDK 21 (or 17) before first Android build**; AGP 8.x tops out at JDK 21. Native module (Kotlin/CameraX) builds with the same JDK/Gradle |
| Emulator | 3 AVDs: pixel_34, pixel_34_aosp, pixel_24_aosp | Use pixel_34 for FGS/screen-off monitoring tests (lock + verify frames still arrive); pixel_24_aosp for min-API (24) checks |
| Physical device | None connected (`adb devices` empty) | — |
| Linux desktop | Toolchain ✓ | Fast local runs + `sqflite_common_ffi` storage tests before deploying to Android |
| Dev media tooling | ffmpeg ✓ (`/usr/bin/ffmpeg`), `/dev/video0` + `/dev/video1`, PulseAudio + capture-capable ALSA (`pcmC0D0c`) | Enables the Appendix D dev sources (live webcam / mic / file playback) with no new pub dependencies — all `dart:io` + `image`, already present |

Phase 0 setup step: pin the JDK (21), confirm a clean `flutter create` + Android debug build
on pixel_34, then scaffold the `camera_service` native module and verify a screen-off
monitoring smoke test before any app code.
## Appendix C — Implementation status (Aug 17 2026)

Phase 0 core (desktop-first dev model) is implemented and unit-tested; the app runs on Linux
with simulated camera/audio so the full pipeline is exercised without hardware.

| Area | Status | Notes |
|---|---|---|
| Core contracts | ✅ | `DetectorConfig`/`Detector`/`FrameDetector`/`AudioDetector`, `Channel`/`ChannelConfig`/`ChannelSettings`, `CameraSession`, `AppSettings` — all JSON round-trip; registry maps detector/channel types to factories |
| Detectors v1 | ✅ | Pixel-diff `MotionDetector` (tolerance 30, threshold ratio, persistence, cooldown), per-type `BabyCryDetector` / `GlassBreakDetector` / `LoudNoiseDetector` reading shared per-window `AudioEventScores` |
| Audio | ✅ (mock) | `AudioEventClassifier` interface; `MockAudioEventClassifier` (RMS + zero-crossing → baby_cry / glass / loud_noise). YAMNet/tflite swap later on mobile (Appendix A1 notes) |
| Pipeline | ✅ | `DetectorPipeline`: multi-trigger, per-detector cooldown, sync broadcast bus. Frames at 160×120 @ 4fps, audio windows @ 1s |
| Event pipeline | ✅ | Trigger → snapshot → route = enabled ∩ `routeToChannelIds` (log-only if none) → per-channel send → SQLite record with per-channel statuses. Delivery retry/backoff (3 attempts) is **not yet implemented** (Phase 1). Worker-isolate offload for encode/SQLite/HTTP is **not yet implemented** (Phase 1) |
| Channels | ✅ | Log + Telegram (bot token/chat ID, `sendPhoto` w/ text fallback, injectable `http.Client`, validation). Email SMTP, Discord, Webhook presets, Pushover, MQTT → later phases per roadmap |
| Storage | ✅ | `SettingsStore` (shared_preferences), `SqliteEventLog` (events + channel statuses), `FileSnapshotStore` (documents dir `snapshots/`, retention purge not yet wired) |
| Simulated sensors | ✅ | `SimulatedCameraSession` (moving-rect scene, PNG snapshots), `SimulatedAudioSource` (silence / baby-cry / glass scenes) — desktop dev stand-ins; real camera + mic come with the native module |
| UI | ✅ | Monitor screen (live view, start/stop, audio-scene demo control), Settings (camera name, per-detector tuning, channel setup incl. Telegram), Event log list. Material 3 shell w/ nav bar. On desktop the preview shows the **simulated** camera scene (moving object), not the on-device camera — real feeds arrive with the mobile `CameraSession` implementations (Android native module / iOS plugin) |
| Verification | ✅ | `flutter analyze` clean; 35 tests pass (detectors incl. loud-noise, pipeline/cooldown, classifier scenes, Telegram via MockClient, settings round-trip, full MonitorController monitoring runs producing motion+baby_cry events and a loud_noise event + snapshot files, grayscaleToRGBA + CameraView widget tests); Linux debug build + launch smoke-tested, monitoring run live-verified via `flutter run` |

Deviations / notes captured during implementation:

- **Motion default threshold lowered 0.08 → 0.03**: at 160×120 a moving 40×40 sim object
  changes only ~4.7% of pixels/frame, below the 8% design default, so motion never fired.
  Sensitivity is user-tunable in Settings.
- **`http_parser` added as a direct dependency** (was transitive) so Telegram photos use the
  real `MediaType`.
- **Pipeline event bus is `sync`** broadcast so deterministic tests don't race microtasks.
- `flutter build linux` requires `lld`; installed `lld` on the dev host (GNU `ld` alone isn't
  found by the Dart native build).
- Verified at runtime: app launches with no exceptions; `events.db` created under
  `~/.local/share/io.securitycam.security_cam/`; monitoring run writes snapshot PNGs and
  records events.

- **Camera preview decode**: v1 used `ui.decodeImageFromPixelsSync`, which throws
  "decodeImageFromPixelsSync is not implemented on Skia" on every frame (Linux = Skia backend),
  aborting paint for the preview and the widgets below it (start/stop button, dropdown border —
  red dot stayed, hit-testing kept working). Fixed by decoding asynchronously with the standard
  `ui.decodeImageFromPixels`, caching the `ui.Image`, disposing only the replaced image
  (generation counter drops stale decodes), and reporting decode errors via `FlutterError`
  instead of throwing in `paint`. Covered by CameraView widget tests.
- **Loud-noise trigger (v1)**: `TriggerType.loudNoise`, off by default (like glass_break),
  `persistenceFrames: 1` (a bang is brief). Mock classifier adds a `loud_noise` class gated by
  `rms > 0.45 && zcr > 0.35` — the RMS floor separates it from the sim scenes (glass ≈ 0.37,
  baby-cry ≈ 0.3, silence ≈ 0; `AudioScene.bang` = full-window broadband noise, rms ≈ 0.58 →
  score ≈ 0.85). Sim quirk: a loud bang also scores high on `glass` (both are loud broadband
  noise in the mock); a real classifier differentiates later.
- **Planned next (not yet implemented — see Appendix D)**: desktop dev camera/audio sources
  (live webcam + video-file playback + mic + audio-file playback, switchable in Settings).
- **Planned next (agreed, not yet implemented) — trigger merging**: prevent bursts of
  notifications by merging triggers that fire within a short window.
  - `TriggerBatcher` between `pipeline.triggers` and `EventPipeline`: opens a batch on the first
    trigger, captures the snapshot immediately (moment of interest), then flushes after a fixed
    `notificationMergeWindow` (single `Timer`, no extending → notification delay bounded at the
    window). Triggers after a flush start a new batch. Window = 0 disables merging (pass-through).
  - `EventPipeline.handleBatch`: **one merged log entry**, one snapshot, one send per routed
    channel (union of the batch's `routeToChannelIds` ∩ enabled), alert text joins labels
    (`Motion + Baby crying detected in Hallway …`), `score` = max of the batch.
  - **DB v2 migration**: `ALTER TABLE events ADD COLUMN trigger_types TEXT` (nullable, JSON array
    of the individual types, only for merged rows). `trigger_type` stays a **single type** — the
    type for normal events, or the new `TriggerType.merged = 'merged'` constant for merged rows
    (never comma-joined). `RecordedEvent`/`RecordedEventRow` gain `triggerTypes: List<String>`.
  - `AppSettings.notificationMergeWindow` (Duration, default 3 s, 0 = off), JSON round-trip,
    Settings screen slider 0–30 s under a Notifications section. Events screen pretty-prints the
    `triggerTypes` list and uses the first type's icon.
  - Integration-test impact: the existing monitor runs (motion ≈0.5 s + baby_cry ≈1 s) merge into
    one `merged` row with `triggerTypes = {motion, baby_cry}` and a single snapshot file; waits
    bumped past the flush time.

## Appendix D — Desktop dev sources: live camera & audio (planned, Aug 17 2026)

Agreed scope for Phase 0 dev tooling: replace the hardcoded simulated sessions with
Settings-switchable sources so real scenes drive the pipeline during development. Desktop-only —
the mobile `camera_service` native module / iOS plugin are unaffected and ignore these switches.

Confirmed on this host: `ffmpeg` installed, `/dev/video0` + `/dev/video1` capture devices,
PulseAudio with a capture-capable ALSA card (`pcmC0D0c`). No new pub dependencies — everything
below uses `dart:io` (`Process`) + the already-present `image` package.

### Settings (AppSettings, JSON round-trip, backward-compatible defaults)

- `cameraSource`: `simulated` | `webcam` | `file` — default `simulated`
- `cameraSourcePath`: device path (e.g. `/dev/video0`) or video file path
- `audioSource`: `simulated` | `mic` | `file` — default `simulated`
- `audioSourcePath`: audio file path

### Camera: `FfmpegCameraSession implements CameraSession`

- webcam: `ffmpeg -f v4l2 -framerate <fps> -i <dev> -vf scale=160:120 -pix_fmt gray -f rawvideo pipe:1`
- file: `ffmpeg -re -stream_loop -1 -i <path> -vf scale=160:120 -pix_fmt gray -f rawvideo pipe:1`
- stdout → pure `GrayFrameAssembler` → `GrayscaleBitmap`/`AnalysisFrame` stream at configured fps
  (gray bytes are exactly `GrayscaleBitmap`'s layout — perfect fit).
- `takeSnapshot()` = latest frame → PNG via `image` (same as the sim).
- ffmpeg missing / device busy → readable error into the existing `MonitorState.error` path; sim
  remains the fallback. stderr drained to avoid pipe-block deadlock; process killed in `dispose`.

### Audio: `AudioSource` contract + `FfmpegAudioSource`

- Extract `abstract AudioSource { Stream<AudioWindow> get windows; void start(); void stop(); void dispose(); }`;
  `SimulatedAudioSource` implements it (already matches).
- mic: `ffmpeg -f pulse -i default -ar 16000 -ac 1 -sample_fmt s16le -f s16le pipe:1`
- file: `ffmpeg -re -stream_loop -1 -i <path> -ar 16000 -ac 1 -sample_fmt s16le -f s16le pipe:1`
- stdout → pure `PcmWindowAccumulator` chunks 16 kHz s16le into 1 s `AudioWindow`s
  (Float32 = sample/32768); looped files replay in real time.

### Wiring & UI

- `MonitorController.start()` picks camera/audio session from a small factory on
  `settings.cameraSource`/`audioSource` (sim default); `_camera`/`_audio` typed as the contracts.
- Settings screen: "Camera source" and "Audio source" `DropdownButton`-in-`InputDecorator`
  controls (same pattern as the monitor screen), revealing a path field when webcam/file/mic-file
  is selected. Persisted via the existing Save.

### Tests & verification

- Unit: `GrayFrameAssembler` (arbitrary chunk splits), `PcmWindowAccumulator`, ffmpeg arg builders,
  settings round-trip incl. new fields with old-JSON fallback, source-selection factory.
- Live: webcam → real preview + wave → motion alert + snapshot; recorded clip replayed from a file →
  loops + alerts; mic → windows flow through the pipeline (note: the mock classifier rarely fires
  baby_cry/glass on real speech — expected until YAMNet); bad device path → clear error, sim still works.
