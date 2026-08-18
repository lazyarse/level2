import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/media_naming.dart';

void main() {
  test('formats date-time-cameraName with millisecond uniqueness', () {
    final name = mediaFileName(
      timestamp: DateTime(2026, 8, 18, 10, 30, 0, 123),
      cameraName: 'Hallway',
      extension: 'jpg',
    );
    expect(name, '2026-08-18_10-30-00-123_Hallway.jpg');
  });

  test('zero-pads single-digit fields', () {
    final name = mediaFileName(
      timestamp: DateTime(2026, 1, 2, 3, 4, 5, 6),
      cameraName: 'Cam',
      extension: 'mp4',
    );
    expect(name, '2026-01-02_03-04-05-006_Cam.mp4');
  });

  test('sanitizes unsafe camera-name characters', () {
    final name = mediaFileName(
      timestamp: DateTime(2026, 8, 18, 10, 30, 0, 0),
      cameraName: 'Front Door/1 (up)',
      extension: 'jpg',
    );
    expect(name, '2026-08-18_10-30-00-000_Front_Door_1__up_.jpg');
    expect(name.contains('/'), isFalse);
    expect(name.contains(' '), isFalse);
  });

  test('different timestamps within a second still yield distinct names', () {
    final a = mediaFileName(
      timestamp: DateTime(2026, 8, 18, 10, 30, 0, 1),
      cameraName: 'Hallway',
      extension: 'mp4',
    );
    final b = mediaFileName(
      timestamp: DateTime(2026, 8, 18, 10, 30, 0, 2),
      cameraName: 'Hallway',
      extension: 'mp4',
    );
    expect(a, isNot(b));
  });
}
