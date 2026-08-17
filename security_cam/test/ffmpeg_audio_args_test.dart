import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/sensors/ffmpeg_audio_source.dart';

void main() {
  test('mic argv captures pulse default at 16 kHz mono s16le', () {
    final args = FfmpegAudioSource.buildArgs(source: 'mic', path: 'default');
    expect(args, [
      '-f', 'pulse',
      '-i', 'default',
      '-ar', '16000',
      '-ac', '1',
      '-f', 's16le',
      'pipe:1',
    ]);
  });

  test('file argv loops the clip in real time', () {
    final args = FfmpegAudioSource.buildArgs(source: 'file', path: '/tmp/a.wav');
    expect(args, [
      '-re',
      '-stream_loop', '-1',
      '-i', '/tmp/a.wav',
      '-ar', '16000',
      '-ac', '1',
      '-f', 's16le',
      'pipe:1',
    ]);
  });

  test('unsupported source throws', () {
    expect(
      () => FfmpegAudioSource.buildArgs(source: 'bogus', path: 'x'),
      throwsArgumentError,
    );
  });
}