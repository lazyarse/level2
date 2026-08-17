import 'dart:typed_data';

import '../core/detector.dart';
import '../core/models.dart';

class MotionDetector extends FrameDetector {
  @override
  final DetectorConfig config;
  static const _pixelDiffTolerance = 30;

  GrayscaleBitmap? _previous;
  int _persistenceCount = 0;

  MotionDetector(this.config);

  @override
  String get id => config.type;

  @override
  String get triggerType => TriggerType.motion;

  @override
  Future<void> init() async {}

  @override
  void reset() {
    _previous = null;
    _persistenceCount = 0;
  }

  @override
  Future<void> dispose() async {}

  @override
  DetectionResult analyzeFrame(AnalysisFrame frame) {
    final bitmap = frame.bitmap;
    final prev = _previous;
    _previous = bitmap;
    if (prev == null) {
      return _result(frame.timestamp, 0.0, false);
    }
    final ratio = _diffRatio(prev, bitmap);
    final triggered = _updatePersistence(ratio, frame.timestamp);
    return _result(frame.timestamp, ratio, triggered);
  }

  bool _updatePersistence(double ratio, DateTime timestamp) {
    final above = ratio >= config.threshold;
    _persistenceCount = above ? _persistenceCount + 1 : 0;
    if (_persistenceCount >= config.persistenceFrames) {
      _persistenceCount = 0;
      return true;
    }
    return false;
  }

  double _diffRatio(GrayscaleBitmap a, GrayscaleBitmap b) {
    final width = a.width;
    final height = a.height;
    var changed = 0;
    for (var y = 0; y < height; y++) {
      final rowA = y * width;
      final rowB = y * width;
      for (var x = 0; x < width; x++) {
        final diff = (a.gray[rowA + x] - b.gray[rowB + x]).abs();
        if (diff > _pixelDiffTolerance) changed++;
      }
    }
    return changed / (width * height);
  }

  DetectionResult _result(DateTime ts, double score, bool triggered) {
    return DetectionResult(
      timestamp: ts,
      triggerType: triggerType,
      score: score,
      triggered: triggered,
    );
  }
}

Uint8List buildFrame(int width, int height, int fill) {
  return Uint8List(width * height)..fillRange(0, width * height, fill);
}

Uint8List buildFrameWithRect(
    int width, int height, int fill, int rectX, int rectY, int rectW, int rectH, int rectFill) {
  final buf = Uint8List(width * height)..fillRange(0, width * height, fill);
  for (var y = rectY; y < rectY + rectH && y < height; y++) {
    for (var x = rectX; x < rectX + rectW && x < width; x++) {
      buf[y * width + x] = rectFill;
    }
  }
  return buf;
}
