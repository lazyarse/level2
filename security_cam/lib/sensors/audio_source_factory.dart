import 'dart:io';

import '../core/audio_source.dart';
import '../core/settings.dart';
import 'ffmpeg_audio_source.dart';
import 'mic_audio_source.dart';
import 'simulated_audio_source.dart';

/// Audio source factory.
///
/// On mobile the on-device microphone is always used ([MicAudioSource],
/// 16 kHz mono s16le → 0.975 s windows), ignoring [AppSettings.audioSource].
/// On desktop: `simulated` (default) → generated scenes;
/// `mic`/`file` → [FfmpegAudioSource].
AudioSource buildAudioSource(AppSettings settings) {
  if (Platform.isAndroid || Platform.isIOS) {
    return MicAudioSource();
  }
  switch (settings.audioSource) {
    case AudioInput.mic:
      return FfmpegAudioSource(AudioInput.mic, 'default');
    case AudioInput.file:
      final path = settings.audioSourcePath?.trim();
      if (path == null || path.isEmpty) {
        throw ArgumentError(
            'An audio file path is required for audio source "file" — '
            'set it in Settings → Sources.');
      }
      return FfmpegAudioSource(AudioInput.file, path);
    case AudioInput.simulated:
    default:
      return SimulatedAudioSource();
  }
}