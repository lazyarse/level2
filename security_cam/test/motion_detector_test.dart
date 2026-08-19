import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/detector.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/motion_detector.dart';

void main() {
  late MotionDetector detector;
  final base = DateTime(2026, 1, 1, 12, 0, 0);

  DetectorConfig config({double threshold = 0.05, int persistence = 2}) {
    return DetectorConfig(
      type: TriggerType.motion,
      threshold: threshold,
      persistenceFrames: persistence,
    );
  }

  AnalysisFrame frame(int step, Uint8List bytes, int width, int height) {
    return AnalysisFrame(
      timestamp: base.add(Duration(seconds: step)),
      bitmap: GrayscaleBitmap(width, height, bytes),
    );
  }

  setUp(() {
    detector = MotionDetector(config());
  });

  test('first frame primes the detector and does not trigger', () {
    final result = detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16));
    expect(result.triggered, isFalse);
  });

  test('identical frames never trigger', () {
    for (var step = 0; step < 6; step++) {
      final result =
          detector.analyzeFrame(frame(step, buildFrame(16, 16, 140), 16, 16));
      expect(result.triggered, isFalse, reason: 'frame $step');
    }
  });

  test('moving object triggers after persistenceFrames', () {
    expect(
        detector
            .analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16))
            .triggered,
        isFalse);
    expect(
        detector
            .analyzeFrame(
                frame(1, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16))
            .triggered,
        isFalse);
    final result = detector.analyzeFrame(
        frame(2, buildFrameWithRect(16, 16, 140, 4, 4, 4, 4, 30), 16, 16));
    expect(result.triggered, isTrue);
    expect(result.score, greaterThan(0.0));
  });

  test('below-threshold jitter does not trigger', () {
    detector = MotionDetector(config(threshold: 0.5));
    for (var step = 0; step < 4; step++) {
      final bytes = buildFrame(16, 16, 140);
      if (step % 2 == 0) bytes[0] = 141;
      final result = detector.analyzeFrame(frame(step, bytes, 16, 16));
      expect(result.triggered, isFalse, reason: 'frame $step');
    }
  });

  test('trigger resets persistence so detector must re-arm', () {
    final positions = [0, 2, 4, 2, 0, 2];
    for (var step = 0; step < positions.length; step++) {
      final p = positions[step];
      final bytes = buildFrameWithRect(16, 16, 140, p, p, 4, 4, 30);
      final result = detector.analyzeFrame(frame(step, bytes, 16, 16));
      final expected = step == 2 || step == 4;
      expect(result.triggered, expected, reason: 'frame $step');
    }
  });

  test('change inside a region triggers; same change outside does not', () {
    detector.regions = const [
      DetectionRegion(
          id: 'r1', shape: 'rect', label: 'doorway', points: [0.0, 0.0, 0.5, 0.5]),
    ];
    // Outside case: move a rect only in the bottom-right quadrant (outside the
    // [0,0.5]x[0,0.5] region). Region-relative ratio stays 0 -> never triggers.
    detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16));
    detector.analyzeFrame(
        frame(1, buildFrameWithRect(16, 16, 140, 8, 8, 8, 8, 30), 16, 16));
    detector.analyzeFrame(
        frame(2, buildFrameWithRect(16, 16, 140, 10, 10, 8, 8, 30), 16, 16));
    expect(detector.analyzeFrame(
            frame(3, buildFrameWithRect(16, 16, 140, 12, 12, 8, 8, 30), 16, 16))
        .triggered, isFalse);

    // Inside case: move a rect within the top-left quadrant (inside the region).
    // Two consecutive above-threshold diffs (4x4=16 px / 64 region px = 0.25) arm
    // the persistence counter.
    detector.reset();
    detector.analyzeFrame(frame(4, buildFrame(16, 16, 140), 16, 16));
    detector.analyzeFrame(
        frame(5, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16));
    expect(detector.analyzeFrame(
            frame(6, buildFrameWithRect(16, 16, 140, 4, 4, 4, 4, 30), 16, 16))
        .triggered, isTrue);
  });

  test('small region denominator keeps thresholds meaningful', () {
    detector = MotionDetector(config(threshold: 0.2, persistence: 2));
    detector.regions = const [
      DetectionRegion(
          id: 'r1', shape: 'rect', label: 'q1', points: [0.0, 0.0, 0.5, 0.5]),
    ];
    detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16));
    detector.analyzeFrame(frame(1, buildFrameWithRect(16, 16, 140, 1, 1, 4, 4, 30), 16, 16));
    expect(detector.analyzeFrame(frame(2, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16))
        .triggered, isTrue);
  });

  test('empty regions = legacy whole-frame behavior', () {
    expect(
        detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16)).triggered,
        isFalse);
    detector.analyzeFrame(frame(1, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16));
    expect(detector.analyzeFrame(frame(2, buildFrameWithRect(16, 16, 140, 4, 4, 4, 4, 30), 16, 16))
        .triggered, isTrue);
  });
}