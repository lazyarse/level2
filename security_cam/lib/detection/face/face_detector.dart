import 'package:flutter/foundation.dart';

import '../../core/detector.dart';
import '../../core/models.dart';
import '../regions/region_filter.dart';
import 'face_engine.dart';
import 'mock_face_engine.dart';
import 'tflite_face_engine.dart';

/// Face-detection trigger. Runs on color analysis frames (motion-gated by the
/// pipeline). Persistence/threshold/cooldown come from [DetectorConfig].
///
/// Detection is async (TFLite background isolate), so the real work lives in
/// [analyzeFrameAsync]; [analyzeFrame] is a no-op non-trigger for the sync path.
class FaceDetector extends FrameDetector {
  @override
  final DetectorConfig config;
  final FaceEngine _engine;
  final bool _ownsEngine;

  int _persistenceCount = 0;

  /// Builds the platform engine lazily if [engine] is not provided.
  FaceDetector(this.config, {FaceEngine? engine})
      : _engine = engine ?? buildFaceEngine(),
        _ownsEngine = engine == null;

  @override
  String get id => config.type;

  @override
  String get triggerType => TriggerType.face;

  @override
  Future<void> init() async {
    if (_ownsEngine) await _engine.init();
  }

  @override
  void reset() {
    _persistenceCount = 0;
  }

  @override
  Future<void> dispose() async {
    if (_ownsEngine) await _engine.dispose();
  }

  @override
  DetectionResult analyzeFrame(AnalysisFrame frame) {
    return DetectionResult(
      timestamp: frame.timestamp,
      triggerType: triggerType,
      score: 0,
      triggered: false,
    );
  }

  @override
  Future<DetectionResult> analyzeFrameAsync(AnalysisFrame frame) async {
    final color = frame.color;
    if (color == null) {
      return _result(frame.timestamp, 0, false);
    }
    final faces = await _engine.detectFaces(color);
    if (regions.isNotEmpty) {
      faces = [
        for (final f in faces)
          if (rectOverlapsAny(
            regions,
            f.box.$1 / color.width,
            f.box.$2 / color.height,
            (f.box.$3 - f.box.$1) / color.width,
            (f.box.$4 - f.box.$2) / color.height,
          ))
            f,
      ];
    }
    if (faces.isEmpty) {
      _persistenceCount = 0;
      return _result(frame.timestamp, 0, false);
    }
    final maxScore = faces.map((f) => f.score).reduce((a, b) => a > b ? a : b);
    final above = maxScore >= config.threshold;
    _persistenceCount = above ? _persistenceCount + 1 : 0;
    if (_persistenceCount >= config.persistenceFrames) {
      _persistenceCount = 0;
      return _result(frame.timestamp, maxScore, true);
    }
    return _result(frame.timestamp, maxScore, false);
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

/// Returns the platform-appropriate engine: TFLite on mobile, mock elsewhere
/// (mirrors `buildAudioClassifier`). Kept here to avoid a circular import with
/// the sensors factory; real desktop dev smoke tests construct
/// [TfliteFaceEngine] directly.
FaceEngine buildFaceEngine() {
  if (defaultTargetPlatform == TargetPlatform.android ||
      defaultTargetPlatform == TargetPlatform.iOS) {
    return TfliteFaceEngine();
  }
  return MockFaceEngine();
}