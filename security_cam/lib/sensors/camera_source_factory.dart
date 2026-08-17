import '../core/camera_session.dart';
import '../core/settings.dart';
import 'ffmpeg_camera_session.dart';
import 'simulated_camera_session.dart';

/// Dev-time camera source factory: `simulated` (default) → the moving-rect
/// scene; `webcam`/`file` → [FfmpegCameraSession].
///
/// Dev-time only: the mobile `camera_service` module / iOS plugin always use
/// the on-device camera and ignore [AppSettings.cameraSource]. To remove this
/// dependency once prototyping is over, delete the `webcam`/`file` branches
/// (and `ffmpeg_camera_session.dart`), keeping the sim as the desktop fallback.
CameraSession buildCameraSession(AppSettings settings) {
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