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
    pipeline.processFrame(AnalysisFrame(
      timestamp: base,
      bitmap: GrayscaleBitmap(16, 16, buildFrame(16, 16, 140)),
    ));
    pipeline.processFrame(AnalysisFrame(
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
    pipeline.processFrame(AnalysisFrame(
      timestamp: base,
      bitmap: GrayscaleBitmap(16, 16, buildFrame(16, 16, 140)),
    ));
    pipeline.processFrame(AnalysisFrame(
      timestamp: base,
      bitmap: GrayscaleBitmap(16, 16, rect(2, 2)),
    ));
    expect(events, hasLength(1));

    // Within cooldown: next motion frame must not re-trigger.
    pipeline.processFrame(AnalysisFrame(
      timestamp: base.add(const Duration(seconds: 30)),
      bitmap: GrayscaleBitmap(16, 16, rect(6, 6)),
    ));
    expect(events, hasLength(1));

    // Outside cooldown: triggers again.
    pipeline.processFrame(AnalysisFrame(
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
}