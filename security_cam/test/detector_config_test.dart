import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/detector.dart';

void main() {
  test('motionGated defaults to false', () {
    const c = DetectorConfig(type: 'face');
    expect(c.motionGated, false);
  });

  test('motionGated JSON round-trips', () {
    const c = DetectorConfig(type: 'face', motionGated: true);
    final back = DetectorConfig.fromJson(c.toJson());
    expect(back.motionGated, true);
  });

  test('missing motionGated falls back to false', () {
    final back = DetectorConfig.fromJson({'type': 'face'});
    expect(back.motionGated, false);
  });
}