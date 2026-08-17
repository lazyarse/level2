import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:security_cam/core/channel.dart';
import 'package:security_cam/core/settings.dart';
import 'package:security_cam/storage/secret_store.dart';
import 'package:security_cam/storage/settings_store.dart';

class _FakeSecretStore implements SecretStore {
  final Map<String, String> _map = {};

  @override
  Future<void> delete(String key) async {
    _map.remove(key);
  }

  @override
  Future<String?> read(String key) async => _map[key];

  @override
  Future<void> write(String key, String value) async {
    _map[key] = value;
  }

  Map<String, String> get all => _map;
}

AppSettings _withTelegram(String token, String chatId) =>
    AppSettings.defaults().copyWith(
      channelConfigs: [
        ChannelConfig(
          id: 'telegram',
          type: 'telegram',
          settingsJson: {'botToken': token, 'chatId': chatId},
        ),
      ],
    );

void main() {
  late _FakeSecretStore secrets;

  setUp(() {
    secrets = _FakeSecretStore();
    SharedPreferences.setMockInitialValues({});
  });

  test('save strips the bot token from the persisted JSON', () async {
    final store = await SettingsStore.open(secrets: secrets);
    await store.save(_withTelegram('123:ABC', '42'));

    final raw = (await SharedPreferences.getInstance())
        .getString('app_settings_v1');
    expect(raw, isNotNull);
    expect(raw, isNot(contains('123:ABC')));
    expect(raw, contains('"chatId":"42"'));
  });

  test('load injects the token from the secret store into settings', () async {
    await secrets.write('channel.telegram.botToken', '123:ABC');
    final store = await SettingsStore.open(secrets: secrets);
    await store.save(_withTelegram('', '42'));

    final loaded = await store.load();
    final tg = loaded.channelConfigs.firstWhere((c) => c.id == 'telegram');
    expect(tg.settingsJson['botToken'], '123:ABC');
    expect(tg.settingsJson['chatId'], '42');
  });

  test('legacy inline token migrates to the secret store and is stripped',
      () async {
    SharedPreferences.setMockInitialValues({
      'app_settings_v1': jsonEncode(_withTelegram('legacy:token', '7').toJson()),
    });
    final store = await SettingsStore.open(secrets: secrets);

    final loaded = await store.load();
    final tg = loaded.channelConfigs.firstWhere((c) => c.id == 'telegram');
    expect(tg.settingsJson['botToken'], 'legacy:token',
        reason: 'token still usable in memory');
    expect(secrets.all['channel.telegram.botToken'], 'legacy:token',
        reason: 'migrated into the secret store');
    final raw = (await SharedPreferences.getInstance())
        .getString('app_settings_v1');
    expect(raw, isNot(contains('legacy:token')),
        reason: 'migration save left it out of the persisted JSON');
  });

  test('log channel (no secrets) round-trips unchanged', () async {
    final store = await SettingsStore.open(secrets: secrets);
    await store.save(AppSettings.defaults());

    final loaded = await store.load();
    expect(loaded.channelConfigs.map((c) => c.id), contains('log'));
    expect(secrets.all, isEmpty);
  });
}