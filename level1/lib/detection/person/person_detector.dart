import 'package:flutter/foundation.dart';

import '../../core/detector.dart';
import '../../core/models.dart';
import 'mock_person_engine.dart';
import 'person_engine.dart';
import 'yolo_person_engine.dart';

/// Person-detection trigger. Runs on color analysis frames (motion-gated by the
/// pipeline). Persistence/threshold/cooldown come from [DetectorConfig].
///
/// Detection is async (LiteRT inference), so the real work lives in
/// [analyzeFrameAsync]; [analyzeFrame] is a no-op non-trigger for the sync path.
class PersonDetector extends FrameDetector {
  @override
  final DetectorConfig config;
  final PersonEngine _engine;
  final bool _ownsEngine;

  int _persistenceCount = 0;

  /// Builds the platform engine lazily if [engine] is not provided.
  PersonDetector(this.config, {PersonEngine? engine})
      : _engine = engine ?? buildPersonEngine(),
        _ownsEngine = engine == null;

  @override
  String get id => config.type;

  @override
  String get triggerType => TriggerType.person;

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
    final people = await _engine.detectPersons(color);
    if (people.isEmpty) {
      _persistenceCount = 0;
      return _result(frame.timestamp, 0, false);
    }
    final maxScore = people.map((p) => p.$5).reduce((a, b) => a > b ? a : b);
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

/// Returns the platform-appropriate engine: the real YOLO engine on Android/iOS,
/// mock elsewhere (mirrors `buildFaceEngine`). Real desktop dev smoke tests
/// construct [YoloPersonEngine] directly.
PersonEngine buildPersonEngine() {
  if (defaultTargetPlatform == TargetPlatform.android ||
      defaultTargetPlatform == TargetPlatform.iOS) {
    return YoloPersonEngine();
  }
  return MockPersonEngine();
}