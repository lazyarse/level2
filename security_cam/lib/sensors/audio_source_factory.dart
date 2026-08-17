import '../core/audio_source.dart';
import '../core/settings.dart';
import 'ffmpeg_audio_source.dart';
import 'simulated_audio_source.dart';

/// Dev-time audio source factory: `simulated` (default) → generated scenes;
/// `mic`/`file` → [FfmpegAudioSource].
///
/// Dev-time only: the mobile `camera_service` module / iOS plugin always use
/// the on-device microphone and ignore [AppSettings.audioSource]. To remove
/// this dependency once prototyping is over, delete the `mic`/`file` branches
/// (and `ffmpeg_audio_source.dart`), keeping the sim as the desktop fallback.
AudioSource buildAudioSource(AppSettings settings) {
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