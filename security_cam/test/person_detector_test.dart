import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/detector.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/person/mock_person_engine.dart';
import 'package:security_cam/detection/person/person_detector.dart';

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
    final d = PersonDetector(
      const DetectorConfig(type: TriggerType.person, persistenceFrames: 1),
      engine: MockPersonEngine()..persons.add((0, 0, 1, 1, 0.9)),
    );
    await d.init();
    final r = await d.analyzeFrameAsync(frame(base));
    expect(r.triggered, false);
    await d.dispose();
  });

  test('person above threshold triggers after persistence', () async {
    final engine = MockPersonEngine()..persons.add((0, 0, 1, 1, 0.9));
    final d = PersonDetector(
      const DetectorConfig(
          type: TriggerType.person, threshold: 0.7, persistenceFrames: 2),
      engine: engine,
    );
    await d.init();
    await d.analyzeFrameAsync(frame(base, c: color(140)));
    final r = await d.analyzeFrameAsync(
        frame(base.add(const Duration(seconds: 1)), c: color(140)));
    expect(r.triggered, true);
    expect(r.triggerType, TriggerType.person);
    await d.dispose();
  });

  test('person below threshold does not trigger', () async {
    final engine = MockPersonEngine()..persons.add((0, 0, 1, 1, 0.5));
    final d = PersonDetector(
      const DetectorConfig(
          type: TriggerType.person, threshold: 0.7, persistenceFrames: 1),
      engine: engine,
    );
    await d.init();
    expect(
        (await d.analyzeFrameAsync(frame(base, c: color(140)))).triggered,
        false);
    await d.dispose();
  });

  test('no person detections does not trigger', () async {
    final d = PersonDetector(
      const DetectorConfig(type: TriggerType.person, persistenceFrames: 1),
      engine: MockPersonEngine(),
    );
    await d.init();
    final r = await d.analyzeFrameAsync(frame(base, c: color(140)));
    expect(r.triggered, false);
    expect(r.score, 0);
    await d.dispose();
  });

  test('result carries max person score', () async {
    final engine = MockPersonEngine()
      ..persons.addAll([
        (0, 0, 1, 1, 0.6),
        (1, 1, 2, 2, 0.95),
      ]);
    final d = PersonDetector(
      const DetectorConfig(type: TriggerType.person, persistenceFrames: 1),
      engine: engine,
    );
    await d.init();
    final r = await d.analyzeFrameAsync(frame(base, c: color(140)));
    expect(r.triggered, true);
    expect(r.score, closeTo(0.95, 1e-9));
    await d.dispose();
  });

  test('reset clears persistence', () async {
    final engine = MockPersonEngine()..persons.add((0, 0, 1, 1, 0.9));
    final d = PersonDetector(
      const DetectorConfig(type: TriggerType.person, persistenceFrames: 2),
      engine: engine,
    );
    await d.init();
    await d.analyzeFrameAsync(frame(base, c: color(140)));
    d.reset();
    final r = await d.analyzeFrameAsync(
        frame(base.add(const Duration(seconds: 1)), c: color(140)));
    expect(r.triggered, false);
    await d.dispose();
  });
}