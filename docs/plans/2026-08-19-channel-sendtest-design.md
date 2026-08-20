# Channel Test-Alert (sendTest) — Design

Date: 2026-08-19
Status: Draft (implementation plan follows)

## Goal

Add the missing UI wiring for the per-channel **"Send test"** button, so a user
can verify a configured channel (Telegram, email, webhook, Pushover, log) delivers
correctly without waiting for a real event. The channel-layer primitive already
exists everywhere.

## Current state (verified from code, 2026-08-19)

- **`sendTest()` is already in the contract** — `Channel` interface
  (`lib/core/channel.dart:51-65`) declares `Future<void> sendTest();` and **all
  five** channels implement it:
  - `LogChannel.sendTest` (`lib/channels/log_channel.dart:25-31`) — appends
    `AlertMessage(triggerType: 'test', text: 'Test alert from $id')` in memory.
  - `TelegramChannel.sendTest` (`lib/channels/telegram_channel.dart:106-108`) —
    text-only `sendMessage`.
  - `EmailChannel.sendTest` (`lib/channels/email_channel.dart:89-96`) — SMTP
    subject+body "Security Cam: test alert".
  - `WebhookChannel.sendTest` (`lib/channels/webhook_channel.dart:157-163`) and
    `PushoverChannel.sendTest` (`lib/channels/pushover_channel.dart:99-105`) —
    delegate to `send()` with a text-only `AlertMessage`.
- **No caller exists** — no button, controller method, or pipeline hook invokes
  `sendTest()` (grep confirms zero wiring).
- **Channels only exist inside a running `EventPipeline`** — `MonitorController`
  (`lib/state/monitor_controller.dart`) builds the pipeline per `start()`; it has
  no standalone channel access. But the registries are pure Dart:
  `channelRegistry` (`lib/core/registries.dart:56-62`) and
  `buildChannelSettings` (`:66-81`) can build any channel from a `ChannelConfig`.
- **Secrets are only in-memory.** `SettingsStore` strips `secretFields` on persist
  and re-injects on load (`lib/storage/settings_store.dart:40-75,87-101`), so a
  test-send must build from the in-memory `AppSettings`, not raw persisted JSON.
- **Validation available** — every channel implements `String? validate()`
  (null = OK); gating the button on `validate()` of the draft settings avoids
  pointless network calls.
- **Settings UI** — per-channel cards in `_channelCard`
  (`lib/ui/settings_screen.dart:425-452`), fields in `_channelFields`
  (`:454-588`), save path `_save()` (`:590-657`). Test key convention:
  `ValueKey('webhookPreset_${config.id}')` etc.; a test button would use
  `ValueKey('sendTest_${config.id}')`. The log channel is hidden from the UI
  (`if (c.type != 'log')` at `:230-232`).

## Design

### 1. `MonitorController.sendTest(ChannelConfig config) → Future<String>`

Pure-Dart, no platform deps:
1. Build `ChannelSettings` via `buildChannelSettings(config.type,
   config.settingsJson)` — this is the in-memory `settingsJson`, so secrets are
   present.
2. Build the channel via `channelRegistry[config.type]!(config)`.
3. Short-circuit: if `channel.validate() != null`, return `'invalid: <reason>'`
   without touching the network.
4. `await channel.sendTest()`; return `'delivered'` on success, `'failed:
   <error>'` on throw.

No snapshot is involved — every channel's test path is text-only.

### 2. Settings UI: per-channel "Send test" button

- Inside `_channelCard`'s `Column`, after `..._channelFields(config)`, add an
  `OutlinedButton` **"Send test"** (`ValueKey('sendTest_${config.id}')`).
- **Enabled state**: build the channel settings from the **draft** (current text
  controllers, same way `_save()` does) and call `validate()`; disabled while
  invalid. This avoids round-tripping through `_save()` and gives immediate
  feedback on incomplete fields.
- On tap: `final result = await controller.sendTest(config)`; show a `SnackBar`
  (`delivered` / `failed: …` / `invalid: …`). Disable the button while in flight.
- The hidden log channel gets no button.

### 3. Testability

- Unit tests avoid real network: exercise `sendTest` with the `log` channel
  (pure in-memory) and with an **invalid** config (e.g. webhook with empty URL →
  `validate()` short-circuit, no HTTP). Widget tests assert button presence,
  enable/disable vs. validation state, and SnackBar on tap (using a config whose
  `validate()` returns null but whose `sendTest` is cheap — `log` is excluded
  from UI, so use an invalid-draft assertion for disable and a mocked result via
  the controller's return value where practical).

## Verification

- **Unit tests** (`test/monitor_controller_test.dart`): log channel → `delivered`;
  invalid webhook (empty URL) → `invalid:`; throwing channel → `failed:`.
- **Widget tests** (`test/settings_screen_test.dart`): button per visible channel,
  disabled when draft invalid, SnackBar on success path.
- **Existing suite**: `flutter test` + `flutter analyze` green on Linux desktop.
- **Optional manual**: `flutter run -d linux`, configure a real webhook, tap Send
  test, confirm delivery.

## Deferred / not in this phase

- Test-send with a snapshot attachment (design contract is text-only; the live
  channel-delivery-testing plan covers real snapshot sends).
- Send test to **multiple** channels at once ("test all") or bulk status report.
- Surfacing delivery as `ChannelDeliveryResult` (currently unused scaffolding;
  the `'delivered'`/`'failed'` string convention is kept).

## Risks

- Low. Purely additive UI + a pure-Dart controller method. The only subtlety is
  building the channel from the **draft** (for validation) vs. the **saved**
  settings (for the actual send); both are in-memory and secrets-complete, and the
  plan makes the draft explicit.