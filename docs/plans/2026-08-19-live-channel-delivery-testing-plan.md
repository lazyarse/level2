# Live Channel Delivery Testing — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add env-gated live channel delivery tests (B5.1/B5.2) — real Telegram, Discord, SMTP, ntfy, Pushover sends — plus a runner script and a documented manual real-world checklist. All self-skipping so the normal `flutter test` suite never hits the network.

**Architecture:** One test file `test/live_channel_delivery_test.dart` reads credentials from `Platform.environment`; each channel group skips with `markTestSkipped` when its `LIVE_*` vars are missing (mirrors `test/ffmpeg_live_test.dart`). A `tool/run_live_channel_tests.sh` wrapper checks env vars and runs the file. A manual checklist is appended to the design doc for on-device/human verification.

**Tech Stack:** Flutter/Dart, `test`'s `markTestSkipped`, existing channels (`TelegramChannel`, `EmailChannel`, and post-Phase-2 `WebhookChannel`/`PushoverChannel`), `Platform.environment`, bash.

**Spec:** `docs/plans/2026-08-19-live-channel-delivery-testing-design.md`

**Execution rule:** NOT scheduled yet — execute only after explicit go-ahead. NOTE: this plan depends on the Phase 2 webhook/pushover channels existing for the ntfy/pushover groups.

---

### Task 1: The env-gated live test file

**Files:**
- Create: `security_cam/test/live_channel_delivery_test.dart`

- [ ] **Step 1: Write the test file**

Structure per the design doc. Core pattern (Telegram group shown; mirror for the others):

```dart
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/channels/telegram_channel.dart';
import 'package:security_cam/core/models.dart';

void main() {
  final env = Platform.environment;

  group('live telegram', () {
    final token = env['LIVE_TELEGRAM_TOKEN'];
    final chatId = env['LIVE_TELEGRAM_CHAT_ID'];
    setUpAll(() {
      if (token == null || chatId == null) {
        markTestSkipped('set LIVE_TELEGRAM_TOKEN/LIVE_TELEGRAM_CHAT_ID to run');
      }
    });

    test('sendTest delivers', () async {
      final c = TelegramChannel(
        id: 'telegram',
        settings: TelegramChannelSettings(botToken: token!, chatId: chatId!),
      );
      await c.sendTest(); // throws on non-2xx
    });

    test('send delivers a snapshot', () async {
      final c = TelegramChannel(
        id: 'telegram',
        settings: TelegramChannelSettings(botToken: token!, chatId: chatId!),
      );
      await c.send(AlertMessage(
        timestamp: DateTime.now(),
        triggerType: 'live-test',
        text: 'Security Cam: live snapshot test',
        snapshot: Snapshot(
          bytes: Uint8List.fromList(kTransparentPng),
          mimeType: 'image/png',
          name: 'live-test.png',
        ),
      ));
    });
  });

  // groups: live discord (LIVE_DISCORD_WEBHOOK_URL, sendTest + snapshot),
  //          live email (LIVE_SMTP_*, sendTest only),
  //          live ntfy (LIVE_NTFY_URL[/LIVE_NTFY_TOKEN], WebhookChannel preset ntfy, sendTest),
  //          live pushover (LIVE_PUSHOVER_TOKEN/LIVE_PUSHOVER_USER, sendTest + snapshot).
}
```

Use a tiny 1×1 PNG constant (or `package:image` to synthesize) for snapshot payloads; assert each channel's non-2xx throw would fail the test (i.e. no try/catch swallowing).

- [ ] **Step 2: Verify it self-skips with no env vars**

Run: `date -R && flutter test test/live_channel_delivery_test.dart`
Expected: PASS (all groups skipped, "set LIVE_* to run").

- [ ] **Step 3: Commit**

```bash
git commit -m "test: env-gated live channel delivery tests (telegram/discord/smtp/ntfy/pushover)"
```

---

### Task 2: Runner script + docs

**Files:**
- Create: `security_cam/tool/run_live_channel_tests.sh`
- Modify: `docs/plans/2026-08-19-live-channel-delivery-testing-design.md` (append the manual checklist)

- [ ] **Step 1: Write the runner**

`security_cam/tool/run_live_channel_tests.sh`:
- if no `LIVE_*` env var is set → print the usage table (each var name + channel) and exit 1.
- else run `date -R && flutter test test/live_channel_delivery_test.dart` and exit with its code.

- [ ] **Step 2: Append the manual checklist to the design doc**

Add the real-world checklist (Gmail app password SMTP, real Telegram bot, real Discord webhook, ntfy topic on a phone, Pushover app on a phone, on-device audio-trigger + clip playback verification) as a "Manual real-world checklist" section with step-by-step instructions and pass criteria.

- [ ] **Step 3: Verify + commit**

Run: `date -R && security_cam/tool/run_live_channel_tests.sh`
Expected: exits 1 with usage (no env vars — correct gate behavior). Then:
```bash
git add security_cam/tool/run_live_channel_tests.sh docs/plans/2026-08-19-live-channel-delivery-testing-design.md
git commit -m "tool: live channel delivery runner + real-world verification checklist"
```

---

### Task 3: Full-suite regression + doc note

- [ ] **Step 1: Confirm the normal suite stays green and offline**

Run: `date -R && flutter test`
Expected: all pass; the live file reports skipped groups only (no network).

- [ ] **Step 2: Update the main design doc**

Flip the §B9.4 "Deferred (per user directive): channel delivery tests including live Telegram (B5.1/B5.2)" note to point at this plan.

- [ ] **Step 3: Commit**

```bash
git add docs
git commit -m "docs: live channel delivery tests implemented (env-gated)"
```

---

## Self-Review notes

- **Spec coverage:** live test file (Task 1) ✓; runner + checklist (Task 2) ✓; offline-suite regression + doc note (Task 3) ✓.
- **Dependency:** ntfy/pushover groups need the Phase 2 `WebhookChannel`/`PushoverChannel` first — this plan is sequenced after `2026-08-19-webhook-pushover-channels-plan.md`.
- **Not in scope:** CI integration, `getUpdates` delivery confirmation, IMAP inbox polling.
