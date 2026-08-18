import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/core/settings.dart';

void main() {
  test('defaults contain motion, baby_cry, glass_break detectors and log channel', () {
    final settings = AppSettings.defaults();
    expect(settings.cameraName, 'Hallway');
    expect(settings.cameraSource, CameraSource.simulated);
    expect(settings.cameraSourcePath, isNull);
    expect(settings.audioSource, AudioInput.simulated);
    expect(settings.audioSourcePath, isNull);
    expect(settings.detectorConfigs.keys,
        containsAll(['motion', 'baby_cry', 'glass_break']));
    expect(settings.detectorConfigs['motion']!.routeToChannelIds,
        contains('telegram'));
    expect(settings.channelConfigs.map((c) => c.id), contains('log'));
  });

  test('JSON round-trip preserves settings', () {
    final settings = AppSettings.defaults().copyWith(
      cameraSource: CameraSource.webcam,
      cameraSourcePath: '/dev/video0',
      audioSource: AudioInput.file,
      audioSourcePath: '/tmp/a.wav',
    );
    final restored = AppSettings.fromJson(settings.toJson());
    expect(restored.cameraName, settings.cameraName);
    expect(restored.cameraSource, CameraSource.webcam);
    expect(restored.cameraSourcePath, '/dev/video0');
    expect(restored.audioSource, AudioInput.file);
    expect(restored.audioSourcePath, '/tmp/a.wav');
    expect(restored.detectorConfigs.keys, settings.detectorConfigs.keys);
    expect(
      restored.detectorConfigs['motion']!.threshold,
      settings.detectorConfigs['motion']!.threshold,
    );
    expect(
      restored.detectorConfigs['motion']!.cooldown,
      settings.detectorConfigs['motion']!.cooldown,
    );
    expect(restored.channelConfigs.length, settings.channelConfigs.length);
    expect(restored.notificationMergeWindow, settings.notificationMergeWindow);
    expect(restored.preRollSeconds, settings.preRollSeconds);
    expect(restored.postRollSeconds, settings.postRollSeconds);
  });

  test('video clip settings round-trip and default to 5/5, record on', () {
    final defaults = AppSettings.defaults();
    expect(defaults.preRollSeconds, 5);
    expect(defaults.postRollSeconds, 5);
    expect(defaults.recordVideo, isTrue);
    expect(defaults.videoQuality, VideoQuality.lowest);

    final custom = defaults.copyWith(
      preRollSeconds: 8,
      postRollSeconds: 12,
      recordVideo: false,
      videoQuality: VideoQuality.fhd,
    );
    final restored = AppSettings.fromJson(custom.toJson());
    expect(restored.preRollSeconds, 8);
    expect(restored.postRollSeconds, 12);
    expect(restored.recordVideo, isFalse);
    expect(restored.videoQuality, VideoQuality.fhd);
  });

  test('old JSON without clip fields falls back to 5/5, record on', () {
    final restored = AppSettings.fromJson(const {'cameraName': 'Nursery'});
    expect(restored.preRollSeconds, 5);
    expect(restored.postRollSeconds, 5);
    expect(restored.recordVideo, isTrue);
    expect(restored.videoQuality, VideoQuality.lowest);
  });

  test('video quality labels quantify each tier', () {
    expect(VideoQuality.values, ['lowest', 'sd', 'hd', 'fhd', 'uhd', 'highest']);
    expect(VideoQuality.label('lowest'), 'Lowest (device minimum)');
    expect(VideoQuality.label('sd'), 'SD (480p)');
    expect(VideoQuality.label('hd'), 'HD (720p)');
    expect(VideoQuality.label('fhd'), 'Full HD (1080p)');
    expect(VideoQuality.label('uhd'), 'UHD (4K)');
    expect(VideoQuality.label('highest'), 'Highest (device maximum)');
    expect(VideoQuality.label('bogus'), 'Lowest (device minimum)');
  });

  test('empty JSON falls back to defaults', () {
    final restored = AppSettings.fromJson(const {});
    expect(restored.cameraName, 'Hallway');
    expect(restored.cameraSource, CameraSource.simulated);
    expect(restored.cameraSourcePath, isNull);
    expect(restored.audioSource, AudioInput.simulated);
    expect(restored.audioSourcePath, isNull);
    expect(restored.detectorConfigs, isNotEmpty);
  });

  test('old JSON without source fields falls back to simulated', () {
    final restored = AppSettings.fromJson(const {'cameraName': 'Nursery'});
    expect(restored.cameraName, 'Nursery');
    expect(restored.cameraSource, CameraSource.simulated);
    expect(restored.audioSource, AudioInput.simulated);
    expect(restored.cameraSourcePath, isNull);
    expect(restored.audioSourcePath, isNull);
  });

  test('legacy JSON with only the log channel merges in default channels', () {
    final restored = AppSettings.fromJson(const {
      'channelConfigs': [
        {'id': 'log', 'type': 'log', 'enabled': true},
      ],
    });
    expect(restored.channelConfigs.map((c) => c.id).toList(),
        ['log', 'telegram', 'email', 'discord']);
    expect(restored.channelConfigs.firstWhere((c) => c.id == 'log').enabled,
        isTrue);
    for (final c in restored.channelConfigs.where((c) => c.id != 'log')) {
      expect(c.enabled, isFalse, reason: 'merged channels default to disabled');
    }
  });

  test('stored channel settings are preserved, not clobbered by defaults', () {
    final restored = AppSettings.fromJson(const {
      'channelConfigs': [
        {
          'id': 'log',
          'type': 'log',
          'enabled': true,
          'settings': <String, dynamic>{},
        },
        {
          'id': 'email',
          'type': 'email',
          'enabled': true,
          'settings': {'host': 'smtp.example.com', 'port': 587},
        },
      ],
    });
    final email = restored.channelConfigs.firstWhere((c) => c.id == 'email');
    expect(email.enabled, isTrue);
    expect(email.settingsJson['host'], 'smtp.example.com');
    expect(restored.channelConfigs.map((c) => c.id),
        containsAll(['log', 'telegram', 'email', 'discord']));
    expect(restored.channelConfigs, hasLength(4));
  });

  test('copyWith updates only provided fields', () {
    final settings = AppSettings.defaults();
    final updated = settings.copyWith(cameraName: 'Nursery');
    expect(updated.cameraName, 'Nursery');
    expect(updated.detectorConfigs, settings.detectorConfigs);
    expect(updated.cameraSource, settings.cameraSource);
  });

  test('copyWith clears the source path when switching back to simulated', () {
    final settings = AppSettings.defaults().copyWith(
      cameraSource: CameraSource.webcam,
      cameraSourcePath: '/dev/video0',
    );
    final restored = settings.copyWith(
      cameraSource: CameraSource.simulated,
      clearCameraSourcePath: true,
    );
    expect(restored.cameraSource, CameraSource.simulated);
    expect(restored.cameraSourcePath, isNull);
  });

  test('copyWith clears the audio path when switching back to simulated', () {
    final settings = AppSettings.defaults().copyWith(
      audioSource: AudioInput.file,
      audioSourcePath: '/tmp/a.wav',
    );
    final restored = settings.copyWith(
      audioSource: AudioInput.simulated,
      clearAudioSourcePath: true,
    );
    expect(restored.audioSource, AudioInput.simulated);
    expect(restored.audioSourcePath, isNull);
  });

  test('toJson omits null paths', () {
    final json = AppSettings.defaults().toJson();
    expect(json.containsKey('cameraSourcePath'), isFalse);
    expect(json.containsKey('audioSourcePath'), isFalse);
  });

  test('analysis resolution defaults to balanced (320x240)', () {
    final s = AppSettings.defaults();
    expect(s.analysisResolution, AnalysisResolution.balanced);
    final (w, h) = AnalysisResolution.size(s.analysisResolution);
    expect((w, h), (320, 240));
  });

  test('analysis resolution JSON round-trips', () {
    final s = AppSettings.defaults().copyWith(
      analysisResolution: AnalysisResolution.high,
    );
    final back = AppSettings.fromJson(s.toJson());
    expect(back.analysisResolution, AnalysisResolution.high);
  });

  test('missing analysisResolution falls back to balanced', () {
    final back = AppSettings.fromJson(const {});
    expect(back.analysisResolution, AnalysisResolution.balanced);
  });

  test('preset labels', () {
    expect(AnalysisResolution.label(AnalysisResolution.low), 'Low (160x120)');
    expect(AnalysisResolution.label(AnalysisResolution.balanced), 'Balanced (320x240)');
    expect(AnalysisResolution.label(AnalysisResolution.high), 'High (640x480)');
  });

  test('defaults include a face detector, disabled and motion-gated', () {
    final s = AppSettings.defaults();
    final face = s.detectorConfigs[TriggerType.face];
    expect(face, isNotNull);
    expect(face!.enabled, false);
    expect(face.motionGated, true);
    expect(face.threshold, 0.7);
  });
}