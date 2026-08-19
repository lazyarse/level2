import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/detector.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/face/face_detector.dart';
import 'package:security_cam/detection/face/face_engine.dart';
import 'package:security_cam/detection/face/mock_face_engine.dart';

void main() {
  final base = DateTime(2026, 1, 1, 12, 0, 0);

  ColorBitmap color(int fill) {
    final bgr = Uint8List(3 * 3 * 3)..fillRange(0, 3 * 3 * 3, fill);
    return ColorBitmap(3, 3, bgr);
  }

  AnalysisFrame frame(DateTime ts, {ColorBitmap? c}) => AnalysisFrame(
        timestamp: ts,
        bitmap: GrayscaleBitmap(3, 3, Uint8List(9)),
        color: c,
      );

  test('no color frame never triggers', () async {
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, persistenceFrames: 1),
      engine: MockFaceEngine()..faces.add(const FaceDetection(box: (0, 0, 1, 1), score: 0.9)),
    );
    await d.init();
    final r = await d.analyzeFrameAsync(frame(base));
    expect(r.triggered, false);
    await d.dispose();
  });

  test('face above threshold triggers after persistence', () async {
    final engine = MockFaceEngine()
      ..faces.add(const FaceDetection(box: (0, 0, 1, 1), score: 0.9));
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.7, persistenceFrames: 2),
      engine: engine,
    );
    await d.init();
    await d.analyzeFrameAsync(frame(base, c: color(140)));
    expect((await d.analyzeFrameAsync(frame(base.add(const Duration(seconds: 1)), c: color(140)))).triggered, true);
    await d.dispose();
  });

  test('face below threshold does not trigger', () async {
    final engine = MockFaceEngine()
      ..faces.add(const FaceDetection(box: (0, 0, 1, 1), score: 0.5));
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.7, persistenceFrames: 1),
      engine: engine,
    );
    await d.init();
    expect((await d.analyzeFrameAsync(frame(base, c: color(140)))).triggered, false);
    await d.dispose();
  });

  test('result carries max face score', () async {
    final engine = MockFaceEngine()
      ..faces.addAll([
        const FaceDetection(box: (0, 0, 1, 1), score: 0.6),
        const FaceDetection(box: (1, 1, 2, 2), score: 0.95),
      ]);
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.5, persistenceFrames: 1),
      engine: engine,
    );
    await d.init();
    final r = await d.analyzeFrameAsync(frame(base, c: color(140)));
    expect(r.triggered, true);
    expect(r.score, closeTo(0.95, 1e-9));
    await d.dispose();
  });

  test('reset clears persistence', () async {
    final engine = MockFaceEngine()
      ..faces.add(const FaceDetection(box: (0, 0, 1, 1), score: 0.9));
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.5, persistenceFrames: 2),
      engine: engine,
    );
    await d.init();
    await d.analyzeFrameAsync(frame(base, c: color(140)));
    d.reset();
    expect((await d.analyzeFrameAsync(frame(base.add(const Duration(seconds: 1)), c: color(140)))).triggered, false);
    await d.dispose();
  });

  // Rect region covering the left half of the frame.
  const halfRegion = DetectionRegion(
      id: 'r1', shape: 'rect', label: 'left', points: [0.0, 0.0, 0.5, 1.0]);

  test('face outside all regions does not trigger', () async {
    // Frame is 3x3 px. Box (1.7,0.4)-(2.8,0.6) -> normalized x 0.567..0.933,
    // outside the left-half region [0,0.5]x[0,1].
    final engine = MockFaceEngine()
      ..faces.add(const FaceDetection(
          box: (1.7, 0.4, 2.8, 0.6), score: 0.9));
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.5, persistenceFrames: 1),
      engine: engine,
    );
    d.regions = const [halfRegion];
    await d.init();
    final r = await d.analyzeFrameAsync(frame(base, c: color(140)));
    expect(r.triggered, isFalse);
    await d.dispose();
  });

  test('face overlapping a region triggers', () async {
    // Box (1.2,0.4)-(2.4,0.6) -> normalized x 0.4..0.8, crosses the x=0.5 edge.
    final engine = MockFaceEngine()
      ..faces.add(const FaceDetection(
          box: (1.2, 0.4, 2.4, 0.6), score: 0.9));
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.5, persistenceFrames: 1),
      engine: engine,
    );
    d.regions = const [halfRegion];
    await d.init();
    final r = await d.analyzeFrameAsync(frame(base, c: color(140)));
    expect(r.triggered, isTrue);
    await d.dispose();
  });

  test('empty regions = all faces pass', () async {
    final engine = MockFaceEngine()
      ..faces.add(const FaceDetection(
          box: (0.9, 0.1, 0.95, 0.2), score: 0.9));
    final d = FaceDetector(
      const DetectorConfig(type: TriggerType.face, threshold: 0.5, persistenceFrames: 1),
      engine: engine,
    );
    await d.init();
    final r = await d.analyzeFrameAsync(frame(base, c: color(140)));
    expect(r.triggered, isTrue);
    await d.dispose();
  });
}