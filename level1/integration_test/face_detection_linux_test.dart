import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:integration_test/integration_test.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/face/tflite_face_engine.dart';

/// Runs on `flutter test integration_test/face_detection_linux_test.dart -d linux`.
/// Uses the REAL BlazeFace engine:
///   - a blank frame yields zero detections (sanity + load check),
///   - bundled real-world images each yield at least one plausible face box.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  /// Asset -> rationale. All license-safe: OpenCV sample data (BSD-3-Clause)
  /// and scikit-image sample data (public domain / BSD).
  const faceAssets = <String, String>{
    'messi5.jpg': 'integration_test/assets/messi5.jpg',
    'astronaut.png': 'integration_test/assets/astronaut.png',
    'camera.png': 'integration_test/assets/camera.png',
  };

  /// Loads an asset and converts it to a BGR [ColorBitmap].
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

  test('tflite face engine loads and runs on a blank frame', () async {
    final engine = TfliteFaceEngine();
    await engine.init();
    final frame = ColorBitmap(
      128,
      128,
      Uint8List(128 * 128 * 3)..fillRange(0, 128 * 128 * 3, 128),
    );
    final faces = await engine.detectFaces(frame);
    expect(faces, isEmpty);
    await engine.dispose();
  });

  for (final MapEntry(:key, :value) in faceAssets.entries) {
    test('detects a real face in $key', () async {
      final engine = TfliteFaceEngine();
      await engine.init();
      final frame = await loadBgr(value);
      final faces = await engine.detectFaces(frame);
      expect(faces, isNotEmpty, reason: 'no face detected in $key');
      final box = faces.first.box;
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