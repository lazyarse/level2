import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/face/face_engine.dart';
import 'package:security_cam/detection/face/tflite_face_engine.dart';

/// Runs on `flutter test integration_test/face_detection_linux_test.dart -d linux`.
/// Uses the REAL BlazeFace engine against a synthetic face-free image: asserts
/// the engine loads and returns zero detections (sanity), and stays alive.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

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
}
