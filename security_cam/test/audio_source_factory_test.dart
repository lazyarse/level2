import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/settings.dart';
import 'package:security_cam/sensors/audio_source_factory.dart';
import 'package:security_cam/sensors/ffmpeg_audio_source.dart';
import 'package:security_cam/sensors/simulated_audio_source.dart';

void main() {
  test('default settings build the simulated source', () {
    final audio = buildAudioSource(AppSettings.defaults());
    expect(audio, isA<SimulatedAudioSource>());
  });

  test('mic source builds an ffmpeg source', () {
    final audio = buildAudioSource(
        AppSettings.defaults().copyWith(audioSource: AudioInput.mic));
    expect(audio, isA<FfmpegAudioSource>());
  });

  test('file source builds an ffmpeg source with the clip path', () {
    final audio = buildAudioSource(AppSettings.defaults().copyWith(
      audioSource: AudioInput.file,
      audioSourcePath: '/tmp/a.wav',
    ));
    expect(audio, isA<FfmpegAudioSource>());
  });

  test('file without a path throws a readable error', () {
    expect(
      () => buildAudioSource(
          AppSettings.defaults().copyWith(audioSource: AudioInput.file)),
      throwsArgumentError,
    );
  });
}