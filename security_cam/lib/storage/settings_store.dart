import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../core/settings.dart';

class SettingsStore {
  static const _key = 'app_settings_v1';
  final SharedPreferences _prefs;

  SettingsStore(this._prefs);

  static Future<SettingsStore> open() async {
    final prefs = await SharedPreferences.getInstance();
    return SettingsStore(prefs);
  }

  Future<AppSettings> load() async {
    final raw = _prefs.getString(_key);
    if (raw == null) return AppSettings.defaults();
    try {
      return AppSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return AppSettings.defaults();
    }
  }

  Future<void> save(AppSettings settings) async {
    await _prefs.setString(_key, jsonEncode(settings.toJson()));
  }
}