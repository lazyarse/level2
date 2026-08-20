# Live Channel Delivery Testing — Design

Date: 2026-08-19
Status: Draft (implementation plan follows; NOT yet scheduled for implementation)

## Goal

Resolve the deferred item *"channel delivery tests including live Telegram (B5.1/B5.2)"*
(main design doc §B9.4). Verify that each real channel actually delivers an alert against
the live service — opt-in, env-gated, never part of the normal suite, plus a documented
manual real-world checklist.

## Why live tests are deferred-by-design

`flutter test` runs the whole `test/` tree. Live delivery hits external services and needs
real credentials, so it must be **opt-in and self-skipping**: when the required env vars
are absent, the tests skip (`markTestSkipped` — the same pattern as
`test/ffmpeg_live_test.dart`, which skips when `ffmpeg` is missing). This keeps the CI/normal
`flutter test` run green with no external calls.

## Design

### `test/live_channel_delivery_test.dart` (env-gated, Linux)

Read credentials from `Platform.environment` (works under `flutter test` on the host; no
`--dart-define` needed). Each group is self-skipping:

| Channel | Required env vars | Assertion |
|---|---|---|
| Telegram | `LIVE_TELEGRAM_TOKEN`, `LIVE_TELEGRAM_CHAT_ID` | `sendTest()` + `send()` with a real JPEG snapshot: no throw; HTTP 2xx. (Delivery verified by the bot, not via `getUpdates` long-poll — avoids flaky waits.) |
| Discord | `LIVE_DISCORD_WEBHOOK_URL` | `sendTest()` + snapshot multipart: no throw. |
| Email | `LIVE_SMTP_HOST`, `LIVE_SMTP_PORT`, `LIVE_SMTP_USERNAME`, `LIVE_SMTP_PASSWORD`, `LIVE_SMTP_FROM`, `LIVE_SMTP_TO` | `sendTest()`: no throw (SMTP handshake ok). Actual inbox delivery is not assertable without IMAP — documented limitation. |
| Webhook (ntfy) | `LIVE_NTFY_URL` (optional `LIVE_NTFY_TOKEN`) | `sendTest()` via the `webhook` preset: no throw. |
| Pushover | `LIVE_PUSHOVER_TOKEN`, `LIVE_PUSHOVER_USER` | `sendTest()` + snapshot: no throw. |

Every case prints a readable "delivered to X" line so the run doubles as a smoke log.
Failures surface as normal test failures (a real bug or a dead credential).

### `security_cam/tool/run_live_channel_tests.sh`

A small host script (mirrors `run_android_integration_tests.sh` style) that:
1. checks at least one `LIVE_*` var is set (else prints usage),
2. runs `date -R && flutter test test/live_channel_delivery_test.dart`.

Runs with the shell environment, so vars are supplied as `LIVE_TELEGRAM_TOKEN=... ./tool/run_live_channel_tests.sh`.

### Manual real-world checklist (the design doc's §Roadmap checklist, formalized)

A `docs/plans/` checklist appended to this design doc (or a `security_cam/docs/` file) with
concrete steps, expanded from the main design doc lines 420–422:
- **Email**: Gmail app password (2-Step Verification) → `sendTest` lands in the inbox.
- **Telegram**: real bot token + chat → alert + snapshot arrive in the chat.
- **Discord**: real webhook → message + attached snapshot land in the channel.
- **Webhook (ntfy)**: push a notification to a phone's ntfy app (topic subscribe).
- **Pushover**: push to the phone's Pushover app.
- **On-device**: run monitoring on a real Android phone; play a real baby-cry/glass-break
  sample at the phone; verify triggers fire and clips play with (post-audio-phase) sound;
  tune per-site thresholds and cooldown.

## Testing strategy

- The env-gated test file is **skipped by default**; the normal `flutter test` stays green
  (assert this in the plan: the suite runs with zero `LIVE_*` vars and skips all groups).
- When run with real credentials, it is the B5.1/B5.2 delivery proof.
- No emulator, no mock — the point is real endpoints (the mocked `MockClient` unit tests
  already cover request shapes).

## Risks

- Live services are flaky/rate-limited — each group is isolated so one failure doesn't
  mask others; the script runs groups independently.
- Credentials in the shell environment — documented to use a throwaway bot/webhook and a
  personal (non-phone-critical) account.
- The live test is a dev-only tool; never wired into any automated CI gate.
