import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/settings.dart';

void main() {
  test('defaults contain motion, baby_cry, glass_break detectors and log channel', () {
    final settings = AppSettings.defaults();
    expect(settings.cameraName, 'Hallway');
    expect(settings.detectorConfigs.keys,
        containsAll(['motion', 'baby_cry', 'glass_break']));
    expect(settings.detectorConfigs['motion']!.routeToChannelIds,
        contains('telegram'));
    expect(settings.channelConfigs.map((c) => c.id), contains('log'));
  });

  test('JSON round-trip preserves settings', () {
    final settings = AppSettings.defaults();
    final restored = AppSettings.fromJson(settings.toJson());
    expect(restored.cameraName, settings.cameraName);
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
    expect(restored.detectorConfigs, isNotEmpty);
  });

  test('copyWith updates only provided fields', () {
    final settings = AppSettings.defaults();
    final updated = settings.copyWith(cameraName: 'Nursery');
    expect(updated.cameraName, 'Nursery');
    expect(updated.detectorConfigs, settings.detectorConfigs);
  });
}