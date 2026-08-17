import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/settings.dart';

void main() {
  test('defaults contain motion, baby_cry, glass_break detectors and log channel', () {
    final settings = AppSettings.defaults();
    expect(settings.cameraName, 'Hallway');
    expect(settings.cameraSource, CameraSource.simulated);
    expect(settings.cameraSourcePath, isNull);
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
    );
    final restored = AppSettings.fromJson(settings.toJson());
    expect(restored.cameraName, settings.cameraName);
    expect(restored.cameraSource, CameraSource.webcam);
    expect(restored.cameraSourcePath, '/dev/video0');
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
  });

  test('empty JSON falls back to defaults', () {
    final restored = AppSettings.fromJson(const {});
    expect(restored.cameraName, 'Hallway');
    expect(restored.cameraSource, CameraSource.simulated);
    expect(restored.cameraSourcePath, isNull);
    expect(restored.detectorConfigs, isNotEmpty);
  });

  test('old JSON without camera source fields falls back to simulated', () {
    final restored = AppSettings.fromJson(const {'cameraName': 'Nursery'});
    expect(restored.cameraName, 'Nursery');
    expect(restored.cameraSource, CameraSource.simulated);
    expect(restored.cameraSourcePath, isNull);
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

  test('toJson omits cameraSourcePath when null', () {
    final json = AppSettings.defaults().toJson();
    expect(json.containsKey('cameraSourcePath'), isFalse);
  });
}