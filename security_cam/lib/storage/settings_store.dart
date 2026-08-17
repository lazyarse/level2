import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../core/channel.dart';
import '../core/registries.dart';
import '../core/settings.dart';
import 'secret_store.dart';

class SettingsStore {
  static const _key = 'app_settings_v1';
  final SharedPreferences _prefs;
  final SecretStore _secrets;

  SettingsStore(this._prefs, {SecretStore? secrets})
      : _secrets = secrets ?? defaultSecretStore(_prefs);

  static Future<SettingsStore> open({SecretStore? secrets}) async {
    final prefs = await SharedPreferences.getInstance();
    return SettingsStore(prefs, secrets: secrets);
  }

  Future<AppSettings> load() async {
    final raw = _prefs.getString(_key);
    final settings = raw == null ? AppSettings.defaults() : _tryParse(raw);
    return _injectSecrets(settings);
  }

  AppSettings _tryParse(String raw) {
    try {
      return AppSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return AppSettings.defaults();
    }
  }

  /// Pulls channel secrets (e.g. the Telegram bot token) out of the secret
  /// store and into the in-memory settings, migrating any legacy tokens that
  /// were previously persisted inline in the settings JSON.
  Future<AppSettings> _injectSecrets(AppSettings settings) async {
    var migrated = false;
    final channels = <ChannelConfig>[];
    for (final c in settings.channelConfigs) {
      ChannelSettings typed;
      try {
        typed = buildChannelSettings(c.type, c.settingsJson);
      } catch (_) {
        channels.add(c);
        continue;
      }
      var json = c.settingsJson;
      var injected = false;
      for (final field in typed.secretFields) {
        final key = _secretKey(c.id, field);
        final inline = json[field];
        if (inline is String && inline.isNotEmpty) {
          // Legacy token still persisted in the JSON → move it to the secret
          // store. The in-memory value stays (channels/UI still use it);
          // save() strips it from the persisted JSON.
          await _secrets.write(key, inline);
          migrated = true;
        } else {
          final stored = await _secrets.read(key);
          if (stored != null && stored.isNotEmpty) {
            json = {...json, field: stored};
            injected = true;
          }
        }
      }
      channels.add(injected ? c.copyWith(settingsJson: json) : c);
    }
    final next = settings.copyWith(channelConfigs: channels);
    if (migrated) await save(next);
    return next;
  }

  Future<void> save(AppSettings settings) async {
    final sanitized = settings.copyWith(
      channelConfigs: [
        for (final c in settings.channelConfigs)
          c.copyWith(settingsJson: _stripSecrets(c)),
      ],
    );
    await _prefs.setString(_key, jsonEncode(sanitized.toJson()));
  }

  Map<String, dynamic> _stripSecrets(ChannelConfig config) {
    try {
      final typed = buildChannelSettings(config.type, config.settingsJson);
      if (typed.secretFields.isEmpty) return config.settingsJson;
      return {
        for (final e in config.settingsJson.entries)
          if (!typed.secretFields.contains(e.key)) e.key: e.value,
      };
    } catch (_) {
      return config.settingsJson;
    }
  }

  static String _secretKey(String channelId, String field) =>
      'channel.$channelId.$field';
}