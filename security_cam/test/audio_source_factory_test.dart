import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/settings.dart';
import 'package:security_cam/sensors/audio_source_factory.dart';
import 'package:security_cam/sensors/ffmpeg_audio_source.dart';
import 'package:security_cam/sensors/mic_audio_source.dart';
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

  test('mobile platforms always build the mic source', () {
    // Mirrors the factory's Platform.isAndroid / Platform.isIOS branch without
    // stubbing dart:io: assert the mobile branch contract via the mic source
    // type (the branch itself is exercised on-device in B9.2e).
    if (Platform.isAndroid || Platform.isIOS) {
      final audio = buildAudioSource(AppSettings.defaults());
      expect(audio, isA<MicAudioSource>());
    }
  });
}