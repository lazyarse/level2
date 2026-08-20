import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:integration_test/integration_test.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/person/yolo_person_engine.dart';

/// Runs on `flutter test integration_test/person_detection_linux_test.dart -d linux`.
/// Uses the REAL YOLO26n model:
///   - bundled images that contain people each yield at least one person box,
///   - a blank frame yields zero detections (sanity + load check).
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  const personAssets = <String, String>{
    'messi5.jpg': 'integration_test/assets/messi5.jpg',
    'astronaut.png': 'integration_test/assets/astronaut.png',
    'camera.png': 'integration_test/assets/camera.png',
  };

  Future<ColorBitmap> loadBgr(String assetPath) async {
    final data = await rootBundle.load(assetPath);
    final decoded = img.decodeImage(data.buffer.asUint8List());
    expect(decoded, isNotNull, reason: 'could not decode $assetPath');
    final image = decoded!;
    final bgr = Uint8List(image.width * image.height * 3);
    var i = 0;
    for (var y = 0; y < image.height; y++) {
      for (var x = 0; x < image.width; x++) {
        final p = image.getPixel(x, y);
        bgr[i++] = p.b.toInt();
        bgr[i++] = p.g.toInt();
        bgr[i++] = p.r.toInt();
      }
    }
    return ColorBitmap(image.width, image.height, bgr);
  }

  test('yolo person engine loads and reports no people on a blank frame', () async {
    final engine = YoloPersonEngine();
    await engine.init();
    final frame = ColorBitmap(
      640,
      640,
      Uint8List(640 * 640 * 3)..fillRange(0, 640 * 640 * 3, 128),
    );
    final people = await engine.detectPersons(frame);
    expect(people, isEmpty);
    await engine.dispose();
  });

  for (final MapEntry(:key, :value) in personAssets.entries) {
    test('detects a person in $key', () async {
      final engine = YoloPersonEngine();
      await engine.init();
      final frame = await loadBgr(value);
      final people = await engine.detectPersons(frame);
      expect(people, isNotEmpty, reason: 'no person detected in $key');
      final box = people.first;
      expect(box.$1, greaterThanOrEqualTo(0));
      expect(box.$2, greaterThanOrEqualTo(0));
      expect(box.$3, lessThanOrEqualTo(frame.width.toDouble()));
      expect(box.$4, lessThanOrEqualTo(frame.height.toDouble()));
      expect(box.$3 - box.$1, greaterThan(0));
      expect(box.$4 - box.$2, greaterThan(0));
      await engine.dispose();
    });
  }
}