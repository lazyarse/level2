# Webhook Family + Pushover Channels — Design

Date: 2026-08-19
Status: Draft (implementation plan follows)

## Goal

Finish the alerting side of the approved Phase 2 roadmap
(`docs/plans/2026-08-17-security-cam-app-design.md`, §"Roadmap phasing" Phase 2):
add a **`WebhookChannel`** type with presets (**ntfy / Slack / Teams / custom**, and —
per this session's decision — **folding the existing Discord channel into it**) plus a
**`PushoverChannel`**. Person detection (the other half of Phase 2) is already implemented.

## Locked decisions (from Q&A + main design doc)

| Topic | Decision |
|---|---|
| Discord fate | **Fold `DiscordChannel` into `WebhookChannel`** as the `discord` preset (user decision). Delete the standalone class, registry entry, settings form, and test file. Persisted settings are migrated (`discord` → `webhook` + `preset: discord`) in `AppSettings.fromJson`. |
| Webhook shape | One channel type `webhook` with a `preset` selector. Matches main design doc §"WebhookChannel base (future-proofing)": "Discord, Slack, Teams, ntfy.sh, and custom webhooks are all the same shape… one channel type with presets — `WebhookChannel` base class + a form template per preset". |
| Pushover | New channel type `pushover`, form-encoded POST to `https://api.pushover.net/1/messages.json`; multipart `attachment` when a snapshot exists. |
| Secrets | `webhook` secrets: `webhookUrl` **and** `bearerToken` (Slack/Teams/Discord URLs embed the token, matching the existing Discord treatment). `pushover` secrets: `appToken`, `userKey`. All go through the existing `SecretStore` flow via `secretFields` + `buildChannelSettings`. |
| Channel defaults | New channels are **disabled** defaults (no "Add channel" UI exists; channels are added by editing defaults — consistent with log/telegram/email/discord). The default `discord` id is kept, retyped to `webhook` with `preset: discord`. |
| Delivery pipeline | Unchanged: `Channel.send()` → retry/backoff (3 attempts) → event log per-channel status. Only the channel classes + registry + settings form + tests change. |
| Testing | Pure Dart on Linux via injectable `http.Client` (`MockClient`), **no emulator** (AGENTS.md target preference). |

## `WebhookChannel` contract

`lib/channels/webhook_channel.dart`:

```dart
class WebhookChannelSettings implements ChannelSettings {
  final String preset;      // 'discord' | 'ntfy' | 'slack' | 'teams' | 'custom'
  final String url;         // webhook URL, or ntfy base URL incl. topic path
  final String bearerToken; // optional; ntfy/custom only
  final String title;       // optional; ntfy X-Title only
  final String bodyStyle;   // 'json' | 'text'; custom only (default 'json')
  // type => 'webhook'; secretFields => ['url', 'bearerToken']
}
```

`class WebhookChannel extends Channel`, `type => 'webhook'`, injectable `http.Client`.

Per-preset `send(AlertMessage)` behavior:

| preset | request | snapshot handling |
|---|---|---|
| `discord` | multipart: field `content` = text, file `file` = snapshot JPEG | upload; on non-2xx or upload error → fall back to JSON `{"content": text}` (existing Discord behavior, moved verbatim) |
| `ntfy` | `POST` text/plain body = text; headers `Authorization: Bearer <token>` (when set) + `X-Title: <title>` (when set) | ignored — ntfy publish has no attachment field for plain POSTs (documented limitation) |
| `slack` | `POST` JSON `{"text": text}` | ignored — Slack incoming webhooks are text-only |
| `teams` | `POST` JSON `{"text": text}` (connector card) | ignored |
| `custom` | `POST`; `bodyStyle == 'json'` → JSON `{"text": text}`; `'text'` → raw body; optional Bearer | `json` style only |

`sendTest()`: same transport, body `Security Cam: test alert`.

`validate()` per preset (all reject empty/non-https URLs first):
- `discord`: existing `^https://(?:canary|ptb\.)?discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_-]+$`.
- `slack`: `^https://hooks\.slack\.com/services/T\d+/B\d+/[A-Za-z0-9]+$`.
- `teams`: `^https://[A-Za-z0-9.\-]+\.webhook\.office\.com/webhookbot/.+$`.
- `ntfy`: `https://` + a topic path present (`url.split('://')[1].contains('/')`).
- `custom`: https URL only.

## `PushoverChannel` contract

`lib/channels/pushover_channel.dart`:

```dart
class PushoverChannelSettings implements ChannelSettings {
  final String appToken; // 30 chars, required
  final String userKey;  // 30 chars, required
  final String sound;    // optional device sound name, default ''
  final int priority;    // -2..2, default 0
  // type => 'pushover'; secretFields => ['appToken', 'userKey']
}
```

- `send`: no snapshot → `application/x-www-form-urlencoded` POST
  `{token, user, message, title?}`; with snapshot → multipart with an `attachment` file
  field (JPEG), same `token`/`user`/`message` fields.
- `sendTest`: message `Security Cam: test alert`.
- `validate`: both `appToken` and `userKey` non-empty; https endpoint is constant.
- Error handling: non-2xx → `StateError('Pushover failed (<status>) <body>')` (matches Discord style).

## Settings migration (`discord` → `webhook`)

In `AppSettings.fromJson`, rewrite stored channel configs before merging defaults:

```dart
final stored = (json['channelConfigs'] as List?)
    ?.map((e) {
      final config = ChannelConfig.fromJson(e as Map<String, dynamic>);
      if (config.type != 'discord') return config;
      return ChannelConfig(
        id: config.id,
        type: 'webhook',
        enabled: config.enabled,
        settingsJson: {'preset': 'discord', ...config.settingsJson},
      );
    })
    .toList() ?? const <ChannelConfig>[];
```

One migration point; registry, settings form, and defaults only ever see `webhook`.
`buildChannelSettings('webhook')` defaults `preset` to `'custom'` when absent (safe
fallback for hand-edited JSON). Defaults entry keeps id `'discord'` so existing
stored-settings merge logic (`!stored.any((c) => c.id == d.id)`) still works:

```dart
ChannelConfig(id: 'discord', type: 'webhook',
    settingsJson: {'preset': 'discord'}, enabled: false),
ChannelConfig(id: 'pushover', type: 'pushover', enabled: false),
```

## Settings UI

`lib/ui/settings_screen.dart` — replace the three `discord` switch cases with `webhook`:
a preset `DropdownButton` (discord/ntfy/slack/teams/custom) + fields per preset
(URL + optional bearer token + ntfy title + custom body-style toggle). Add `pushover`
fields (App token, User key, optional sound). Track per-channel draft state with the
existing `_fieldControllers` pattern plus two new maps: `_webhookPreset`, `_webhookBodyStyle`.
Save (`_save`) rebuilds the typed settings from the field controllers (existing pattern).

## Registry / defaults wiring

- `lib/core/registries.dart`: drop `_discordChannel`/`'discord'`; add `'webhook'` and
  `'pushover'` to `channelRegistry`; add both cases to `buildChannelSettings`.
- `lib/core/settings.dart`: defaults as above.
- `lib/core/models.dart`: no changes (`AlertMessage`, `Snapshot` already exist).

## Testing

- `test/webhook_channel_test.dart`: per-preset request assertions via `MockClient`
  (method/headers/body), discord multipart + JSON fallback (ported from
  `test/discord_channel_test.dart`), `sendTest`, non-2xx error text, per-preset
  `validate()`, secret fields.
- `test/pushover_channel_test.dart`: form POST without snapshot, multipart attachment
  with snapshot, `sendTest`, non-2xx error, `validate()`, secret fields.
- `test/settings_test.dart`: defaults contain `webhook` (id `discord`, preset `discord`)
  + `pushover`; legacy `discord`-type JSON migrates to `webhook`+preset; webhook/pushover
  settings round-trip; `buildChannelSettings` handles `'webhook'`/`'pushover'`.
- `test/settings_screen_test.dart`: update discord assertions to the webhook preset UI;
  add pushover form render + save.
- Delete `test/discord_channel_test.dart`.

## Risks / notes

- Discord-fallback path must stay byte-compatible with the current behavior (it is
  moved verbatim).
- ntfy/Slack/Teams are text-only (no snapshot) — documented, not a bug.
- No "Add channel" UI in this pass (consistent with existing channels).
