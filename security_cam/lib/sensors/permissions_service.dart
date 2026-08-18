import 'package:flutter/foundation.dart';
import 'package:permission_handler/permission_handler.dart';

/// Result of a permission check, split into the blocking (monitoring cannot
/// start) and non-blocking (best-effort, e.g. notifications) parts.
class PermissionsResult {
  const PermissionsResult({
    required this.cameraGranted,
    required this.microphoneGranted,
    required this.notificationsGranted,
  });

  final bool cameraGranted;
  final bool microphoneGranted;
  final bool notificationsGranted;

  bool get monitorGranted => cameraGranted && microphoneGranted;
}

/// Requests the runtime permissions the app needs to monitor.
///
/// Desktop (and tests) use a no-op that grants everything; the mobile
/// implementation goes through `permission_handler` for activity-scoped
/// requests (CAMERA, RECORD_AUDIO, and POST_NOTIFICATIONS on Android 13+).
abstract class PermissionsService {
  const PermissionsService();

  Future<PermissionsResult> ensurePermissions();
}

class NoopPermissionsService extends PermissionsService {
  const NoopPermissionsService();

  @override
  Future<PermissionsResult> ensurePermissions() async =>
      const PermissionsResult(
        cameraGranted: true,
        microphoneGranted: true,
        notificationsGranted: true,
      );
}

class SystemPermissionsService extends PermissionsService {
  const SystemPermissionsService();

  @override
  Future<PermissionsResult> ensurePermissions() async {
    final camera = await Permission.camera.request();
    final microphone = await Permission.microphone.request();
    // POST_NOTIFICATIONS is Android 13+; on lower APIs permission_handler
    // reports granted without prompting.
    final notifications = await Permission.notification.request();
    return PermissionsResult(
      cameraGranted: camera.isGranted,
      microphoneGranted: microphone.isGranted,
      notificationsGranted: notifications.isGranted,
    );
  }
}

/// Picks the permission service for the current platform.
PermissionsService buildPermissionsService() {
  if (!kIsWeb && (defaultTargetPlatform == TargetPlatform.android ||
      defaultTargetPlatform == TargetPlatform.iOS)) {
    return const SystemPermissionsService();
  }
  return const NoopPermissionsService();
}