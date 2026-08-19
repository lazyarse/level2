import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/sensors/ffmpeg_camera_session.dart';

void main() {
  test('webcam argv reads v4l2 and encodes raw gray to stdout', () {
    final args = FfmpegCameraSession.buildArgs(
      source: 'webcam',
      path: '/dev/video0',
      width: 160,
      height: 120,
      fps: 4,
    );
    expect(args, [
      '-f', 'v4l2',
      '-framerate', '4',
      '-i', '/dev/video0',
      '-vf', 'scale=160:120',
      '-pix_fmt', 'bgr24',
      '-f', 'rawvideo',
      'pipe:1',
    ]);
  });

  test('file argv loops the clip in real time', () {
    final args = FfmpegCameraSession.buildArgs(
      source: 'file',
      path: '/tmp/clip.mp4',
      width: 160,
      height: 120,
      fps: 4,
    );
    expect(args, [
      '-re',
      '-stream_loop', '-1',
      '-i', '/tmp/clip.mp4',
      '-vf', 'scale=160:120',
      '-pix_fmt', 'bgr24',
      '-f', 'rawvideo',
      'pipe:1',
    ]);
  });

  test('unsupported source throws', () {
    expect(
      () => FfmpegCameraSession.buildArgs(
        source: 'bogus',
        path: 'x',
        width: 160,
        height: 120,
        fps: 4,
      ),
      throwsArgumentError,
    );
  });
}