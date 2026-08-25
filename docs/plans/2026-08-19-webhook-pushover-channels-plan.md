# Webhook Family + Pushover Channels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish Phase 2 alerting: replace the standalone `DiscordChannel` with a preset-driven `WebhookChannel` (discord / ntfy / slack / teams / custom), add a `PushoverChannel`, migrate persisted `discord` configs, and wire registry + settings defaults + settings UI. Includes the housekeeping commit (stray `pubspec.lock`).

**Architecture:** One `webhook` channel type with a `preset` selector (main design doc §"WebhookChannel base"): `WebhookChannel`/`WebhookChannelSettings` implement the existing `Channel`/`ChannelSettings` contract with per-preset request builders + validators. `PushoverChannel`/`PushoverChannelSettings` add the second new type. `AppSettings.fromJson` migrates legacy `discord` configs to `webhook`+`preset: discord` at load (single migration point), so registry/form/UI stay uniform on `webhook`.

**Tech Stack:** Flutter/Dart, `package:http` (+ `http_parser` for multipart, already a dep), injectable `http.Client`/`MockClient` for tests. No emulator — pure Linux unit/widget tests.

**Spec:** `docs/plans/2026-08-19-webhook-pushover-channels-design.md`

**Deviations from main design doc:** `DiscordChannel` is folded into `WebhookChannel` as the `discord` preset (user decision) rather than leaving it standalone; persisted settings are migrated rather than aliased.

---

### Task 0: Housekeeping — commit the stray pubspec.lock

**Files:**
- Commit: `security_cam/pubspec.lock`

- [ ] **Step 1: Commit the lock file**

`pubspec.lock` currently shows `flutter_litert: dependency: "direct main"` (the change from adding `flutter_litert: ^3.8.0` in the person phase was never committed).

Run: `date -R && git -C /home/tpa/code/level2 add security_cam/pubspec.lock && git -C /home/tpa/code/level2 commit -m "chore: lock flutter_litert as a direct dependency"`
Expected: commit succeeds; `AGENTS.md` stays untracked (per project directive — never commit it).

---

### Task 1: `WebhookChannel` (presets incl. folded Discord) + tests

**Files:**
- Create: `security_cam/lib/channels/webhook_channel.dart`
- Test: `security_cam/test/webhook_channel_test.dart` (Create)
- Delete: `security_cam/lib/channels/discord_channel.dart`
- Delete: `security_cam/test/discord_channel_test.dart`

- [ ] **Step 1: Write the failing tests**

Create `security_cam/test/webhook_channel_test.dart`. Port every `test/discord_channel_test.dart` case into a `discord`-preset group, then add ntfy / slack / teams / custom groups. Key cases:

- discord preset: multipart POST with `content` + `file` when a snapshot exists; JSON `{"content": text}` without a snapshot; on an upload non-2xx, falls back to a JSON content-only request; `sendTest` posts test alert; non-2xx throws `StateError` containing `Webhook failed (<status>)`; `validate()` accepts a well-formed Discord webhook URL and rejects empty / `https://nope.com/x`.
- ntfy: POST with `content-type: text/plain`, body = alert text, `Authorization: Bearer <token>` header when set, `X-Title` when set, and neither header when unset.
- slack: POST JSON body `{"text": "..."}`.
- teams: POST JSON body `{"text": "..."}`.
- custom: `bodyStyle: 'json'` → JSON `{"text": ...}`; `bodyStyle: 'text'` → raw body; bearer header when set.
- per-preset `validate()`: slack rejects a Discord-shaped URL; teams rejects non-`webhook.office.com` URLs; ntfy rejects `https://ntfy.sh` (no topic) and accepts `https://ntfy.sh/mytopic`; custom accepts any https URL and rejects `http://`.
- secret fields: `WebhookChannelSettings(...).secretFields` contains `url` and `bearerToken`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `date -R && flutter test test/webhook_channel_test.dart`
Expected: FAIL — `webhook_channel.dart` not found.

- [ ] **Step 3: Implement `WebhookChannel`**

Create `security_cam/lib/channels/webhook_channel.dart`:

```dart
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;

import '../core/channel.dart';
import '../core/models.dart';

const webhookPresets = ['discord', 'ntfy', 'slack', 'teams', 'custom'];

class WebhookChannelSettings implements ChannelSettings {
  final String preset;
  final String url;
  final String bearerToken;
  final String title;
  final String bodyStyle;

  const WebhookChannelSettings({
    this.preset = 'custom',
    this.url = '',
    this.bearerToken = '',
    this.title = '',
    this.bodyStyle = 'json',
  });

  @override
  String get type => 'webhook';

  @override
  Map<String, dynamic> toJson() => {
        'preset': preset,
        'url': url,
        'bearerToken': bearerToken,
        'title': title,
        'bodyStyle': bodyStyle,
      };

  @override
  List<String> get secretFields => ['url', 'bearerToken'];

  factory WebhookChannelSettings.fromJson(Map<String, dynamic> json) =>
      WebhookChannelSettings(
        preset: json['preset'] as String? ?? 'custom',
        url: json['url'] as String? ?? '',
        bearerToken: json['bearerToken'] as String? ?? '',
        title: json['title'] as String? ?? '',
        bodyStyle: json['bodyStyle'] as String? ?? 'json',
      );
}

class WebhookChannel extends Channel {
  @override
  final String id;
  @override
  final bool enabled;
  @override
  final WebhookChannelSettings settings;
  final http.Client _client;

  WebhookChannel({
    required this.id,
    this.enabled = true,
    required this.settings,
    http.Client? client,
  }) : _client = client ?? http.Client();

  @override
  String get type => 'webhook';

  static final _discordRe = RegExp(
      r'^https://(?:canary|ptb\.)?discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_-]+$');
  static final _slackRe = RegExp(
      r'^https://hooks\.slack\.com/services/T\d+/B\d+/[A-Za-z0-9]+$');
  static final _teamsRe = RegExp(
      r'^https://[A-Za-z0-9.\-]+\.webhook\.office\.com/webhookbot/.+$');

  @override
  Future<void> send(AlertMessage message) async {
    switch (settings.preset) {
      case 'discord':
        await _sendDiscord(message);
      case 'ntfy':
        await _sendNtfy(message);
      case 'slack':
        await _sendJson({'text': message.text});
      case 'teams':
        await _sendJson({'text': message.text});
      default:
        await _sendCustom(message);
    }
  }

  Future<void> _sendDiscord(AlertMessage message) async {
    final snapshot = message.snapshot;
    if (snapshot != null) {
      final request = http.MultipartRequest('POST', Uri.parse(settings.url))
        ..fields['content'] = message.text
        ..files.add(http.MultipartFile.fromBytes(
          'file',
          snapshot.bytes,
          filename: snapshot.name,
          contentType: MediaType.parse(snapshot.mimeType),
        ));
      final streamed = await _client.send(request);
      final response = await http.Response.fromStream(streamed);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        await _sendJson({'content': message.text});
        return;
      }
    } else {
      await _sendJson({'content': message.text});
    }
  }

  Future<void> _sendNtfy(AlertMessage message) async {
    final headers = <String, String>{'content-type': 'text/plain'};
    if (settings.bearerToken.isNotEmpty) {
      headers['Authorization'] = 'Bearer ${settings.bearerToken}';
    }
    if (settings.title.isNotEmpty) {
      headers['X-Title'] = settings.title;
    }
    await _post(headers, message.text);
  }

  Future<void> _sendCustom(AlertMessage message) async {
    final headers = <String, String>{};
    if (settings.bearerToken.isNotEmpty) {
      headers['Authorization'] = 'Bearer ${settings.bearerToken}';
    }
    if (settings.bodyStyle == 'text') {
      headers['content-type'] = 'text/plain';
      await _post(headers, message.text);
    } else {
      await _sendJson({'text': message.text});
    }
  }

  Future<void> _sendJson(Map<String, Object?> body) async {
    await _post(
        {'content-type': 'application/json'}, jsonEncode(body));
  }

  Future<void> _post(Map<String, String> headers, String body) async {
    final response = await _client.post(Uri.parse(settings.url),
        headers: headers, body: body);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError(
          'Webhook failed (${response.statusCode}) ${response.body}');
    }
  }

  @override
  Future<void> sendTest() async {
    await send(AlertMessage(
      timestamp: DateTime.now(),
      triggerType: 'test',
      text: 'Security Cam: test alert',
    ));
  }

  @override
  String? validate() {
    final url = settings.url.trim();
    if (url.isEmpty) return 'Webhook URL is required';
    if (!url.startsWith('https://')) return 'Webhook URL must be https';
    switch (settings.preset) {
      case 'discord':
        if (!_discordRe.hasMatch(url)) {
          return 'Webhook URL is not a valid Discord webhook URL';
        }
      case 'slack':
        if (!_slackRe.hasMatch(url)) {
          return 'Webhook URL is not a valid Slack incoming webhook URL';
        }
      case 'teams':
        if (!_teamsRe.hasMatch(url)) {
          return 'Webhook URL is not a valid Teams webhook URL';
        }
      case 'ntfy':
        final rest = url.substring('https://'.length);
        if (!rest.contains('/')) return 'ntfy topic is missing from the URL';
    }
    return null;
  }
}
```

- [ ] **Step 4: Delete the old Discord files**

Run: `rm security_cam/lib/channels/discord_channel.dart security_cam/test/discord_channel_test.dart`

- [ ] **Step 5: Verify tests pass + analyze clean**

Run: `date -R && flutter test test/webhook_channel_test.dart && flutter analyze`
Expected: webhook tests PASS; analyze clean (no dangling `discord_channel` import — fixed in Task 3).

- [ ] **Step 6: Commit**

```bash
git add security_cam/lib/channels/webhook_channel.dart security_cam/test/webhook_channel_test.dart
git rm security_cam/lib/channels/discord_channel.dart security_cam/test/discord_channel_test.dart
git commit -m "feat: preset-driven WebhookChannel (discord/ntfy/slack/teams/custom), folding Discord"
```

---

### Task 2: `PushoverChannel` + tests

**Files:**
- Create: `security_cam/lib/channels/pushover_channel.dart`
- Test: `security_cam/test/pushover_channel_test.dart` (Create)

- [ ] **Step 1: Write the failing tests**

Create `security_cam/test/pushover_channel_test.dart` (MockClient pattern):
- form-encoded POST (`application/x-www-form-urlencoded`) body contains `token=`, `user=`, `message=Security Cam: test alert` when no snapshot; no `attachment` field.
- with a snapshot → multipart body contains `attachment` and the filename.
- non-2xx → `StateError` containing `Pushover failed (401)`.
- `validate()`: empty appToken → `App token is required`; empty userKey → `User key is required`; valid config → null.
- secret fields: `secretFields` == `['appToken', 'userKey']`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `date -R && flutter test test/pushover_channel_test.dart`
Expected: FAIL — `pushover_channel.dart` not found.

- [ ] **Step 3: Implement `PushoverChannel`**

Create `security_cam/lib/channels/pushover_channel.dart`:

```dart
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;

import '../core/channel.dart';
import '../core/models.dart';

class PushoverChannelSettings implements ChannelSettings {
  final String appToken;
  final String userKey;
  final String sound;
  final int priority;

  const PushoverChannelSettings({
    this.appToken = '',
    this.userKey = '',
    this.sound = '',
    this.priority = 0,
  });

  @override
  String get type => 'pushover';

  @override
  Map<String, dynamic> toJson() => {
        'appToken': appToken,
        'userKey': userKey,
        'sound': sound,
        'priority': priority,
      };

  @override
  List<String> get secretFields => ['appToken', 'userKey'];

  factory PushoverChannelSettings.fromJson(Map<String, dynamic> json) =>
      PushoverChannelSettings(
        appToken: json['appToken'] as String? ?? '',
        userKey: json['userKey'] as String? ?? '',
        sound: json['sound'] as String? ?? '',
        priority: json['priority'] as int? ?? 0,
      );
}

class PushoverChannel extends Channel {
  @override
  final String id;
  @override
  final bool enabled;
  @override
  final PushoverChannelSettings settings;
  final http.Client _client;

  static const _endpoint = 'https://api.pushover.net/1/messages.json';

  PushoverChannel({
    required this.id,
    this.enabled = true,
    required this.settings,
    http.Client? client,
  }) : _client = client ?? http.Client();

  @override
  String get type => 'pushover';

  Map<String, String> _fields(String message) => {
        'token': settings.appToken,
        'user': settings.userKey,
        'message': message,
        if (settings.sound.isNotEmpty) 'sound': settings.sound,
        'priority': settings.priority.toString(),
      };

  @override
  Future<void> send(AlertMessage message) async {
    final snapshot = message.snapshot;
    if (snapshot != null) {
      final request = http.MultipartRequest('POST', Uri.parse(_endpoint))
        ..fields.addAll(_fields(message.text))
        ..files.add(http.MultipartFile.fromBytes(
          'attachment',
          snapshot.bytes,
          filename: snapshot.name,
          contentType: MediaType.parse(snapshot.mimeType),
        ));
      final streamed = await _client.send(request);
      _check(await http.Response.fromStream(streamed));
    } else {
      final response = await _client.post(
        Uri.parse(_endpoint),
        headers: {'content-type': 'application/x-www-form-urlencoded'},
        body: _fields(message.text),
      );
      _check(response);
    }
  }

  @override
  Future<void> sendTest() async {
    await send(AlertMessage(
      timestamp: DateTime.now(),
      triggerType: 'test',
      text: 'Security Cam: test alert',
    ));
  }

  void _check(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError(
          'Pushover failed (${response.statusCode}) ${response.body}');
    }
  }

  @override
  String? validate() {
    if (settings.appToken.isEmpty) return 'App token is required';
    if (settings.userKey.isEmpty) return 'User key is required';
    return null;
  }
}
```

- [ ] **Step 4: Verify**

Run: `date -R && flutter test test/pushover_channel_test.dart && flutter analyze`
Expected: PASS; analyze clean.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/channels/pushover_channel.dart security_cam/test/pushover_channel_test.dart
git commit -m "feat: Pushover channel (form + image attachment)"
```

---

### Task 3: Registry, defaults, migration + tests

**Files:**
- Modify: `security_cam/lib/core/registries.dart`
- Modify: `security_cam/lib/core/settings.dart`
- Test: `security_cam/test/settings_test.dart` (Modify)

- [ ] **Step 1: Write the failing tests**

Add to `security_cam/test/settings_test.dart`:
- defaults contain a channel with id `discord`, `type == 'webhook'`, `settingsJson['preset'] == 'discord'`, disabled; and a `pushover` channel, disabled.
- legacy migration: `AppSettings.fromJson({'channelConfigs': [{'id': 'discord', 'type': 'discord', 'enabled': true, 'settings': {'webhookUrl': 'https://discord.com/api/webhooks/1/abc'}}]})` → the channel has `type == 'webhook'` and `settingsJson['preset'] == 'discord'`.
- webhook/pushover settings JSON round-trip via `AppSettings.fromJson`/`toJson`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `date -R && flutter test test/settings_test.dart`
Expected: FAIL — defaults still `type: 'discord'`.

- [ ] **Step 3: Implement**

`registries.dart`:
- Swap `import '../channels/discord_channel.dart';` → `import '../channels/pushover_channel.dart';` + `import '../channels/webhook_channel.dart';`.
- Remove `_discordChannel`; add:

```dart
Channel _webhookChannel(ChannelConfig c) => WebhookChannel(
      id: c.id,
      enabled: c.enabled,
      settings: WebhookChannelSettings.fromJson(c.settingsJson),
    );

Channel _pushoverChannel(ChannelConfig c) => PushoverChannel(
      id: c.id,
      enabled: c.enabled,
      settings: PushoverChannelSettings.fromJson(c.settingsJson),
    );
```

- `channelRegistry`: replace `'discord': _discordChannel` with `'webhook': _webhookChannel` and add `'pushover': _pushoverChannel`.
- `buildChannelSettings`: replace `case 'discord'` with `case 'webhook': return WebhookChannelSettings.fromJson(json);` and add `case 'pushover': return PushoverChannelSettings.fromJson(json);`.

`settings.dart`:
- Defaults: replace `ChannelConfig(id: 'discord', type: 'discord', enabled: false)` with:
```dart
ChannelConfig(id: 'discord', type: 'webhook',
    settingsJson: {'preset': 'discord'}, enabled: false),
ChannelConfig(id: 'pushover', type: 'pushover', enabled: false),
```
- `fromJson`: add the discord→webhook rewrite shown in the design doc.

- [ ] **Step 4: Verify + full suite**

Run: `date -R && flutter test test/settings_test.dart && flutter test`
Expected: PASS (settings + full unit suite). Then `flutter analyze` → clean.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/core/registries.dart security_cam/lib/core/settings.dart security_cam/test/settings_test.dart
git commit -m "feat: register webhook/pushover channels with discord settings migration"
```

---

### Task 4: Settings UI for webhook + pushover

**Files:**
- Modify: `security_cam/lib/ui/settings_screen.dart`
- Test: `security_cam/test/settings_screen_test.dart` (Modify)

- [ ] **Step 1: Write the failing tests**

Update `security_cam/test/settings_screen_test.dart`:
- replace the `'discord'` type assertion with `'webhook'` (containsAll list) and assert a preset dropdown renders with `discord` selected; entering a URL + selecting preset `slack` saves `type: 'webhook'`, `preset: 'slack'`, `url` set.
- add a pushover case: fields App token / User key render; entering both saves them under `settingsJson`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `date -R && flutter test test/settings_screen_test.dart`
Expected: FAIL — no webhook/pushover form yet.

- [ ] **Step 3: Implement**

`settings_screen.dart`:
- Imports: remove `discord_channel.dart`; add `pushover_channel.dart` + `webhook_channel.dart` (and `core/registries.dart` is not needed — use `webhookPresets` const).
- State: add `final _webhookPreset = <String, String>{};` and `final _webhookBodyStyle = <String, String>{};`.
- `initState`: replace the `case 'discord'` with:
```dart
case 'webhook':
  final s = WebhookChannelSettings.fromJson(c.settingsJson);
  _webhookPreset[c.id] = s.preset;
  _webhookBodyStyle[c.id] = s.bodyStyle;
  _field('${c.id}.url', s.url);
  _field('${c.id}.token', s.bearerToken);
  _field('${c.id}.title', s.title);
case 'pushover':
  final s = PushoverChannelSettings.fromJson(c.settingsJson);
  _field('${c.id}.appToken', s.appToken);
  _field('${c.id}.userKey', s.userKey);
  _field('${c.id}.sound', s.sound);
```
- `_channelFields`: add `case 'webhook'` (preset `DropdownButton` + URL `TextField` + bearer token `TextField`; when preset == ntfy, a title field; when preset == custom, a JSON/text toggle) and `case 'pushover'` (appToken/userKey/sound fields).
- `_save`: add `case 'webhook'` building `WebhookChannelSettings(preset: _webhookPreset[c.id] ?? 'custom', url: _field('${c.id}.url').text.trim(), bearerToken: _field('${c.id}.token').text, title: _field('${c.id}.title').text, bodyStyle: _webhookBodyStyle[c.id] ?? 'json')` and `case 'pushover'` building `PushoverChannelSettings(appToken: ..., userKey: ..., sound: ...)`.

- [ ] **Step 4: Verify**

Run: `date -R && flutter test test/settings_screen_test.dart && flutter analyze`
Expected: PASS; analyze clean.

- [ ] **Step 5: Commit**

```bash
git add security_cam/lib/ui/settings_screen.dart security_cam/test/settings_screen_test.dart
git commit -m "feat: settings UI for webhook presets and Pushover"
```

---

### Task 5: Final verification

- [ ] **Step 1: Full suite + analyze**

Run: `date -R && flutter test && flutter analyze`
Expected: all tests PASS (previous 176 unit tests, minus deleted discord tests, plus new webhook/pushover/settings/UI tests); analyze clean.

- [ ] **Step 2: Linux desktop smoke (optional but recommended)**

Run: `date -R && flutter run -d linux` → Settings tab: confirm the Discord card now shows a preset dropdown set to `discord`, and a new Pushover card; save + restart persists. (Manual smoke; not required for the gate.)

- [ ] **Step 3: Commit any stragglers**

```bash
git status --short   # confirm clean except AGENTS.md
```

---

## Self-Review notes

- **Spec coverage:** Discord folded (Tasks 1/3) ✓; ntfy/Slack/Teams/custom presets (Task 1) ✓; Pushover (Task 2) ✓; registry + defaults + migration (Task 3) ✓; settings UI (Task 4) ✓; housekeeping pubspec.lock (Task 0) ✓; verification (Task 5) ✓.
- **Placeholder scan:** every code-bearing step has the actual code; test steps name exact files/cases.
- **Type consistency:** `WebhookChannelSettings{preset,url,bearerToken,title,bodyStyle}`, `PushoverChannelSettings{appToken,userKey,sound,priority}`, `_webhookPreset`/`_webhookBodyStyle` maps — all referenced consistently across Tasks 1–4.
- **No emulator** — everything runs headless on Linux per AGENTS.md target preference.
- **Risk:** the discord multipart fallback must keep exact current behavior — ported verbatim; covered by the ported test cases.
