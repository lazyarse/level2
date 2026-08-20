import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/settings.dart';
import 'package:security_cam/sensors/camera_source_factory.dart';
import 'package:security_cam/sensors/ffmpeg_camera_session.dart';
import 'package:security_cam/sensors/simulated_camera_session.dart';

void main() {
  test('default settings build the simulated session', () {
    final camera = buildCameraSession(AppSettings.defaults());
    expect(camera, isA<SimulatedCameraSession>());
    expect(camera.cameraId, 'simulated');
  });

  test('webcam source builds an ffmpeg session with the device path', () {
    final camera = buildCameraSession(AppSettings.defaults().copyWith(
      cameraSource: CameraSource.webcam,
      cameraSourcePath: '/dev/video0',
    ));
    expect(camera, isA<FfmpegCameraSession>());
    expect(camera.cameraId, 'webcam');
  });

  test('file source builds an ffmpeg session with the clip path', () {
    final camera = buildCameraSession(AppSettings.defaults().copyWith(
      cameraSource: CameraSource.file,
      cameraSourcePath: '/tmp/clip.mp4',
    ));
    expect(camera, isA<FfmpegCameraSession>());
    expect(camera.cameraId, 'file');
  });

  test('webcam/file without a path throws a readable error', () {
    expect(
      () => buildCameraSession(
          AppSettings.defaults().copyWith(cameraSource: CameraSource.webcam)),
      throwsArgumentError,
    );
    expect(
      () => buildCameraSession(
          AppSettings.defaults().copyWith(cameraSource: CameraSource.file)),
      throwsArgumentError,
    );
  });
}