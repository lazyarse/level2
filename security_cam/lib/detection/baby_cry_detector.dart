import '../core/detector.dart';
import '../core/models.dart';
import 'audio/audio_classifier.dart';

class BabyCryDetector extends AudioDetector {
  @override
  final DetectorConfig config;
  int _persistenceCount = 0;

  BabyCryDetector(this.config);

  @override
  String get id => config.type;

  @override
  String get triggerType => TriggerType.babyCry;

  @override
  Future<void> init() async {}

  @override
  void reset() {
    _persistenceCount = 0;
  }

  @override
  Future<void> dispose() async {}

  @override
  DetectionResult analyzeScores(AudioEventScores scores) {
    final score = scores.scoreOf('baby_cry');
    final triggered = _updatePersistence(score);
    return DetectionResult(
      timestamp: scores.timestamp,
      triggerType: triggerType,
      score: score,
      triggered: triggered,
    );
  }

  bool _updatePersistence(double score) {
    final above = score >= config.threshold;
    _persistenceCount = above ? _persistenceCount + 1 : 0;
    if (_persistenceCount >= config.persistenceFrames) {
      _persistenceCount = 0;
      return true;
    }
    return false;
  }
}
