import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/detector.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/audio/audio_classifier.dart';
import 'package:security_cam/detection/motion_detector.dart';
import 'package:security_cam/detection/pipeline.dart';
import 'package:security_cam/sensors/simulated_audio_source.dart';

void main() {
  final base = DateTime(2026, 1, 1, 12, 0, 0);

  DetectorPipeline build({
    double motionThreshold = 0.01,
    Duration motionCooldown = const Duration(seconds: 60),
    bool babyCryEnabled = true,
    Duration babyCooldown = const Duration(seconds: 60),
  }) {
    return DetectorPipeline(
      classifier: MockAudioEventClassifier(),
      configs: [
        DetectorConfig(
          type: TriggerType.motion,
          enabled: true,
          threshold: motionThreshold,
          persistenceFrames: 1,
          cooldown: motionCooldown,
        ),
        DetectorConfig(
          type: TriggerType.babyCry,
          enabled: babyCryEnabled,
          threshold: 0.5,
          persistenceFrames: 1,
          cooldown: babyCooldown,
        ),
      ],
    );
  }

  test('motion triggers flow through the pipeline', () async {
    final pipeline = build();
    await pipeline.init();
    final events = <TriggerEvent>[];
    final sub = pipeline.triggers.listen(events.add);
    // First frame primes the motion detector.
    await pipeline.processFrame(AnalysisFrame(
      timestamp: base,
      bitmap: GrayscaleBitmap(16, 16, buildFrame(16, 16, 140)),
    ));
    await pipeline.processFrame(AnalysisFrame(
      timestamp: base.add(const Duration(seconds: 1)),
      bitmap: GrayscaleBitmap(16, 16, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30)),
    ));
    expect(events, hasLength(1));
    expect(events.first.triggerType, TriggerType.motion);
    await sub.cancel();
    await pipeline.dispose();
  });

  test('per-detector cooldown suppresses repeat triggers', () async {
    final pipeline = build(motionCooldown: const Duration(seconds: 60));
    await pipeline.init();
    final events = <TriggerEvent>[];
    final sub = pipeline.triggers.listen(events.add);
    Uint8List rect(int x, int y) =>
        buildFrameWithRect(16, 16, 140, x, y, 4, 4, 30);

    // Prime, then trigger at t0.
    await pipeline.processFrame(AnalysisFrame(
      timestamp: base,
      bitmap: GrayscaleBitmap(16, 16, buildFrame(16, 16, 140)),
    ));
    await pipeline.processFrame(AnalysisFrame(
      timestamp: base,
      bitmap: GrayscaleBitmap(16, 16, rect(2, 2)),
    ));
    expect(events, hasLength(1));

    // Within cooldown: next motion frame must not re-trigger.
    await pipeline.processFrame(AnalysisFrame(
      timestamp: base.add(const Duration(seconds: 30)),
      bitmap: GrayscaleBitmap(16, 16, rect(6, 6)),
    ));
    expect(events, hasLength(1));

    // Outside cooldown: triggers again.
    await pipeline.processFrame(AnalysisFrame(
      timestamp: base.add(const Duration(seconds: 61)),
      bitmap: GrayscaleBitmap(16, 16, rect(8, 8)),
    ));
    expect(events, hasLength(2));
    await sub.cancel();
    await pipeline.dispose();
  });

  test('audio windows emit baby cry triggers', () async {
    final pipeline = build();
    await pipeline.init();
    final events = <TriggerEvent>[];
    final sub = pipeline.triggers.listen(events.add);
    await pipeline.processAudio(AudioWindow(
      timestamp: base,
      samples: Float32List(15600),
      sampleRate: 16000,
    ));
    // Silence: no trigger.
    expect(events, hasLength(0));
    await pipeline.processAudio(AudioWindow(
      timestamp: base.add(const Duration(seconds: 1)),
      samples: SimulatedAudioSource.generateWindow(AudioScene.babyCry),
      sampleRate: 16000,
    ));
    expect(events, hasLength(1));
    expect(events.single.triggerType, TriggerType.babyCry);
    await sub.cancel();
    await pipeline.dispose();
  });

  test('disabled detectors are not instantiated', () async {
    final pipeline = build(babyCryEnabled: false);
    await pipeline.init();
    expect(pipeline.frameDetectors, hasLength(1));
    expect(pipeline.audioDetectors, hasLength(0));
    await pipeline.dispose();
  });

  test('gated detectors run only when motion fires', () async {
    final stub = _GatedStubDetector(const DetectorConfig(
      type: 'gated', enabled: true, motionGated: true, persistenceFrames: 1));
    final pipeline = DetectorPipeline(
      classifier: MockAudioEventClassifier(),
      configs: [
        const DetectorConfig(
          type: TriggerType.motion, enabled: true, threshold: 0.01,
          persistenceFrames: 1),
      ],
    );
    await pipeline.init();
    pipeline.debugAddFrameDetector(stub); // injected before subscribing
    final events = <TriggerEvent>[];
    final sub = pipeline.triggers.listen(events.add);

    // Prime the motion detector (no motion on frame 1).
    await pipeline.processFrame(AnalysisFrame(
      timestamp: base,
      bitmap: GrayscaleBitmap(16, 16, buildFrame(16, 16, 140)),
    ));
    expect(stub.asyncCalls, 0);
    expect(events, hasLength(0));

    // Motion fires on frame 2 → gated detector runs.
    await pipeline.processFrame(AnalysisFrame(
      timestamp: base.add(const Duration(seconds: 1)),
      bitmap: GrayscaleBitmap(16, 16, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30)),
    ));
    expect(stub.asyncCalls, 1);
    expect(events.map((e) => e.triggerType), contains('gated'));

    // No motion on frame 3 (identical to frame 2) → gated detector does not
    // run again.
    await pipeline.processFrame(AnalysisFrame(
      timestamp: base.add(const Duration(seconds: 2)),
      bitmap: GrayscaleBitmap(16, 16, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30)),
    ));
    expect(stub.asyncCalls, 1);

    await sub.cancel();
    await pipeline.dispose();
  });
}

/// Gated stub detector: counts how often its async path is invoked.
class _GatedStubDetector extends FrameDetector {
  _GatedStubDetector(this._config);
  final DetectorConfig _config;
  int asyncCalls = 0;

  @override
  DetectorConfig get config => _config;

  @override
  String get id => 'gated-stub';

  @override
  String get triggerType => 'gated';

  @override
  Future<void> init() async {}

  @override
  void reset() {}

  @override
  Future<void> dispose() async {}

  @override
  DetectionResult analyzeFrame(AnalysisFrame frame) =>
      DetectionResult(timestamp: frame.timestamp, triggerType: triggerType, score: 0, triggered: false);

  @override
  Future<DetectionResult> analyzeFrameAsync(AnalysisFrame frame) async {
    asyncCalls++;
    return DetectionResult(timestamp: frame.timestamp, triggerType: triggerType, score: 1, triggered: true);
  }
}