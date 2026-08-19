import 'dart:typed_data';

import '../core/detector.dart';
import '../core/models.dart';
import 'regions/region_filter.dart';

class MotionDetector extends FrameDetector {
  @override
  final DetectorConfig config;
  static const _pixelDiffTolerance = 30;

  GrayscaleBitmap? _previous;
  int _persistenceCount = 0;
  Uint8List? _mask;
  int _maskCount = 0;
  int _maskWidth = 0;
  int _maskHeight = 0;
  List<DetectionRegion>? _maskRegions;

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
    _mask = null;
  }

  @override
  Future<void> dispose() async {}

  @override
  DetectionResult analyzeFrame(AnalysisFrame frame) {
    if (_mask == null ||
        !identical(_maskRegions, regions) ||
        _maskWidth != frame.bitmap.width ||
        _maskHeight != frame.bitmap.height) {
      _rebuildMask(frame.bitmap.width, frame.bitmap.height);
    }
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

  void _rebuildMask(int width, int height) {
    final (mask, count) = pixelMask(regions, width, height);
    _mask = mask;
    _maskCount = count;
    _maskRegions = regions;
    _maskWidth = width;
    _maskHeight = height;
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
    final mask = _mask!;
    final count = _maskCount;
    var changed = 0;
    for (var y = 0; y < a.height; y++) {
      final rowA = y * a.width;
      final rowB = y * a.width;
      for (var x = 0; x < a.width; x++) {
        final idx = rowA + x;
        if (mask[idx] == 0) continue;
        final diff = (a.gray[idx] - b.gray[idx]).abs();
        if (diff > _pixelDiffTolerance) changed++;
      }
    }
    return count == 0 ? 0.0 : changed / count;
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
