import 'dart:io';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Key/value store for channel secrets (bot tokens, SMTP passwords, …).
///
/// Kept behind an interface so the app and tests never depend on the concrete
/// backing store. Mobile uses the keystore-backed [SecureStorageSecretStore];
/// desktop uses [PrefsSecretStore] during prototyping (no keyring required —
/// but note it is NOT the production-secure path for mobile).
abstract class SecretStore {
  Future<String?> read(String key);

  Future<void> write(String key, String value);

  Future<void> delete(String key);
}

/// Keystore-backed secrets (Android Keystore / iOS Keychain / Linux libsecret).
class SecureStorageSecretStore implements SecretStore {
  static const _storage = FlutterSecureStorage();

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) =>
      _storage.write(key: key, value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: key);
}

/// Dev-only store keeping secrets in SharedPreferences. Used on desktop so
/// prototyping works without a Secret Service; the Android/iOS builds use
/// [SecureStorageSecretStore].
class PrefsSecretStore implements SecretStore {
  static const _prefix = 'secret_';
  final SharedPreferences _prefs;

  PrefsSecretStore(this._prefs);

  @override
  Future<String?> read(String key) async => _prefs.getString(_prefix + key);

  @override
  Future<void> write(String key, String value) async {
    await _prefs.setString(_prefix + key, value);
  }

  @override
  Future<void> delete(String key) async {
    await _prefs.remove(_prefix + key);
  }
}

/// Platform-appropriate default store. Mobile = keystore-backed; desktop
/// (dev host) = SharedPreferences-backed.
SecretStore defaultSecretStore(SharedPreferences prefs) {
  if (Platform.isAndroid || Platform.isIOS) {
    return SecureStorageSecretStore();
  }
  return PrefsSecretStore(prefs);
}