import 'dart:io';

import '../core/camera_session.dart';
import '../core/settings.dart';
import 'android_camera_session.dart';
import 'ffmpeg_camera_session.dart';
import 'simulated_camera_session.dart';

/// Camera source factory.
///
/// On Android the native `camera_service` module is always used — the on-device
/// camera, ignoring [AppSettings.cameraSource] (mobile builds never use the
/// dev-time sources). On desktop: `simulated` (default) → the moving-rect scene;
/// `webcam`/`file` → [FfmpegCameraSession].
CameraSession buildCameraSession(AppSettings settings) {
  if (Platform.isAndroid) {
    return AndroidCameraSession(cameraId: 'back');
  }
  switch (settings.cameraSource) {
    case CameraSource.webcam:
    case CameraSource.file:
      final path = settings.cameraSourcePath?.trim();
      if (path == null || path.isEmpty) {
        throw ArgumentError(
            'A device/file path is required for camera source '
            '"${settings.cameraSource}" — set it in Settings → Sources.');
      }
      return FfmpegCameraSession(settings.cameraSource, path);
    case CameraSource.simulated:
    default:
      return SimulatedCameraSession();
  }
}