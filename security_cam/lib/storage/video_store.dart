import 'package:flutter/services.dart';

import '../core/media_naming.dart';

/// Manages video clips for a trigger. On Android this talks to the native
/// camera service (ring buffer in the monitoring FGS, final clip in MediaStore);
/// on other platforms it's a no-op so the app still runs.
abstract class VideoStore {
  /// Captures the pre-roll ring buffer plus [postRollSeconds] of footage after
  /// [triggerAt] and stores the clip. Returns its display name, or null when
  /// unavailable (unsupported platform, not monitoring, or an export already
  /// in progress).
  Future<String?> exportClip({
    required DateTime triggerAt,
    required String cameraName,
    required int preRollSeconds,
    required int postRollSeconds,
  });

  /// Deletes the stored clip by display name.
  Future<void> delete(String name);

  /// Opens the stored clip in the external system player.
  Future<void> open(String name);

  /// Whether a clip with this display name is present in the media store.
  Future<bool> exists(String name);
}

class NoopVideoStore implements VideoStore {
  const NoopVideoStore();

  @override
  Future<String?> exportClip({
    required DateTime triggerAt,
    required String cameraName,
    required int preRollSeconds,
    required int postRollSeconds,
  }) async =>
      null;

  @override
  Future<void> delete(String name) async {}

  @override
  Future<void> open(String name) async {}

  @override
  Future<bool> exists(String name) async => false;
}

/// Android implementation backed by the native `camera_service` module.
class PlatformVideoStore implements VideoStore {
  static const String _cameraChannel = 'io.securitycam.security_cam/camera';

  final MethodChannel _method;

  PlatformVideoStore({MethodChannel? method})
      : _method = method ?? const MethodChannel(_cameraChannel);

  @override
  Future<String?> exportClip({
    required DateTime triggerAt,
    required String cameraName,
    required int preRollSeconds,
    required int postRollSeconds,
  }) async {
    return _method.invokeMethod<String?>('exportVideoClip', {
      'triggerTimestampMs': triggerAt.millisecondsSinceEpoch,
      'cameraName': cameraName,
      'preRollSeconds': preRollSeconds,
      'postRollSeconds': postRollSeconds,
    });
  }

  @override
  Future<void> delete(String name) async {
    await _method.invokeMethod<void>('deleteVideo', {'name': name});
  }

  @override
  Future<void> open(String name) async {
    await _method.invokeMethod<void>('openVideo', {'name': name});
  }

  @override
  Future<bool> exists(String name) async {
    final result = await _method.invokeMethod<bool?>('videoExists', {
      'name': name,
    });
    return result ?? false;
  }
}

/// Builds a fully-qualified clip display name using the shared scheme.
String videoFileName({
  required DateTime timestamp,
  required String cameraName,
}) =>
    mediaFileName(timestamp: timestamp, cameraName: cameraName, extension: 'mp4');
