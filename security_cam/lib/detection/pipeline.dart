import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/detector.dart';
import '../core/models.dart';
import '../core/registries.dart';
import 'audio/audio_classifier.dart';

class DetectorPipeline {
  final AudioEventClassifier classifier;
  final List<FrameDetector> _frameDetectors;
  final List<AudioDetector> _audioDetectors;
  final Map<String, DateTime> _lastTriggerAt = {};
  final StreamController<TriggerEvent> _triggers =
      StreamController<TriggerEvent>.broadcast(sync: true);

  /// Test seam: injects an extra frame detector after construction.
  @visibleForTesting
  void debugAddFrameDetector(FrameDetector detector) {
    _frameDetectors.add(detector);
  }

  DetectorPipeline({
    required this.classifier,
    required List<DetectorConfig> configs,
  })  : _frameDetectors = configs
            .where((c) => c.enabled)
            .map((c) => detectorRegistry[c.type]!(c))
            .whereType<FrameDetector>()
            .toList(),
        _audioDetectors = configs
            .where((c) => c.enabled)
            .map((c) => detectorRegistry[c.type]!(c))
            .whereType<AudioDetector>()
            .toList();

  Stream<TriggerEvent> get triggers => _triggers.stream;

  List<FrameDetector> get frameDetectors => List.unmodifiable(_frameDetectors);

  List<AudioDetector> get audioDetectors => List.unmodifiable(_audioDetectors);

  Future<void> init() async {
    await classifier.init();
    for (final d in _frameDetectors) {
      await d.init();
    }
    for (final d in _audioDetectors) {
      await d.init();
    }
  }

  void reset() {
    _lastTriggerAt.clear();
    for (final d in _frameDetectors) {
      d.reset();
    }
    for (final d in _audioDetectors) {
      d.reset();
    }
  }

  Future<void> processFrame(AnalysisFrame frame) async {
    var motionFired = false;
    for (final d in _frameDetectors) {
      if (d.config.motionGated) continue;
      final result = d.analyzeFrame(frame);
      if (result.triggered) {
        if (d.triggerType == TriggerType.motion) motionFired = true;
        _maybeEmit(d, result);
      }
    }
    if (!motionFired) return;
    for (final d in _frameDetectors) {
      if (!d.config.motionGated) continue;
      final result = await d.analyzeFrameAsync(frame);
      if (result.triggered) _maybeEmit(d, result);
    }
  }

  Future<void> processAudio(AudioWindow window) async {
    final scores = await classifier.classify(window);
    for (final d in _audioDetectors) {
      final result = d.analyzeScores(scores);
      if (result.triggered) _maybeEmit(d, result);
    }
  }

  void _maybeEmit(Detector detector, DetectionResult result) {
    final last = _lastTriggerAt[detector.id];
    final now = result.timestamp;
    if (last != null && now.difference(last) < detector.config.cooldown) return;
    _lastTriggerAt[detector.id] = now;
    _triggers.add(TriggerEvent(
      timestamp: now,
      triggerType: result.triggerType,
      score: result.score,
      detectorId: detector.id,
    ));
  }

  Future<void> dispose() async {
    await classifier.dispose();
    for (final d in _frameDetectors) {
      await d.dispose();
    }
    for (final d in _audioDetectors) {
      await d.dispose();
    }
    await _triggers.close();
  }
}