import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/detector.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/audio/audio_classifier.dart';
import 'package:security_cam/detection/baby_cry_detector.dart';
import 'package:security_cam/detection/glass_break_detector.dart';
import 'package:security_cam/detection/loud_noise_detector.dart';
import 'package:security_cam/sensors/simulated_audio_source.dart';

void main() {
  final base = DateTime(2026, 1, 1, 12, 0, 0);

  AudioEventScores scores({
    double babyCry = 0.0,
    double glass = 0.0,
    double loudNoise = 0.0,
  }) {
    return AudioEventScores(
      timestamp: base,
      classScores: {'baby_cry': babyCry, 'glass': glass, 'loud_noise': loudNoise},
    );
  }

  group('BabyCryDetector', () {
    test('does not trigger below threshold', () {
      final detector = BabyCryDetector(DetectorConfig(
        type: TriggerType.babyCry,
        threshold: 0.5,
        persistenceFrames: 1,
      ));
      final result = detector.analyzeScores(scores(babyCry: 0.2));
      expect(result.triggered, isFalse);
    });

    test('triggers once persistence is met', () {
      final detector = BabyCryDetector(DetectorConfig(
        type: TriggerType.babyCry,
        threshold: 0.5,
        persistenceFrames: 2,
      ));
      expect(detector.analyzeScores(scores(babyCry: 0.8)).triggered, isFalse);
      expect(detector.analyzeScores(scores(babyCry: 0.8)).triggered, isTrue);
    });

    test('does not react to glass scores', () {
      final detector = BabyCryDetector(DetectorConfig(
        type: TriggerType.babyCry,
        threshold: 0.5,
        persistenceFrames: 1,
      ));
      final result = detector.analyzeScores(scores(glass: 0.9));
      expect(result.triggered, isFalse);
      expect(result.score, 0.0);
    });
  });

  group('GlassBreakDetector', () {
    test('does not trigger on baby cry scores', () {
      final detector = GlassBreakDetector(DetectorConfig(
        type: TriggerType.glassBreak,
        threshold: 0.5,
        persistenceFrames: 1,
      ));
      final result = detector.analyzeScores(scores(babyCry: 0.9));
      expect(result.triggered, isFalse);
    });

    test('triggers on glass score', () {
      final detector = GlassBreakDetector(DetectorConfig(
        type: TriggerType.glassBreak,
        threshold: 0.5,
        persistenceFrames: 1,
      ));
      final result = detector.analyzeScores(scores(glass: 0.9));
      expect(result.triggered, isTrue);
      expect(result.score, 0.9);
    });
  });

  group('LoudNoiseDetector', () {
    test('does not trigger below threshold', () {
      final detector = LoudNoiseDetector(DetectorConfig(
        type: TriggerType.loudNoise,
        threshold: 0.5,
        persistenceFrames: 1,
      ));
      final result = detector.analyzeScores(scores(loudNoise: 0.2));
      expect(result.triggered, isFalse);
    });

    test('triggers on loud noise score', () {
      final detector = LoudNoiseDetector(DetectorConfig(
        type: TriggerType.loudNoise,
        threshold: 0.5,
        persistenceFrames: 1,
      ));
      final result = detector.analyzeScores(scores(loudNoise: 0.9));
      expect(result.triggered, isTrue);
      expect(result.score, 0.9);
    });

    test('does not react to baby cry or glass scores', () {
      final detector = LoudNoiseDetector(DetectorConfig(
        type: TriggerType.loudNoise,
        threshold: 0.5,
        persistenceFrames: 1,
      ));
      expect(detector.analyzeScores(scores(babyCry: 0.9)).triggered, isFalse);
      expect(detector.analyzeScores(scores(glass: 0.9)).triggered, isFalse);
    });
  });

  group('MockAudioEventClassifier', () {
    test('classifies baby cry scene as high baby_cry score', () async {
      final classifier = MockAudioEventClassifier();
      await classifier.init();
      final window = AudioWindow(
        timestamp: base,
        samples: SimulatedAudioSource.generateWindow(AudioScene.babyCry),
        sampleRate: SimulatedAudioSource.sampleRate,
      );
      final result = await classifier.classify(window);
      expect(result.scoreOf('baby_cry'), greaterThan(0.5));
      expect(result.scoreOf('glass'), lessThan(0.1));
      await classifier.dispose();
    });

    test('classifies glass scene as high glass score', () async {
      final classifier = MockAudioEventClassifier();
      final window = AudioWindow(
        timestamp: base,
        samples: SimulatedAudioSource.generateWindow(AudioScene.glassBreak),
        sampleRate: SimulatedAudioSource.sampleRate,
      );
      final result = await classifier.classify(window);
      expect(result.scoreOf('glass'), greaterThan(0.5));
      expect(result.scoreOf('baby_cry'), lessThan(0.1));
      expect(result.scoreOf('loud_noise'), lessThan(0.1));
    });

    test('classifies bang scene as high loud_noise score', () async {
      final classifier = MockAudioEventClassifier();
      final window = AudioWindow(
        timestamp: base,
        samples: SimulatedAudioSource.generateWindow(AudioScene.bang),
        sampleRate: SimulatedAudioSource.sampleRate,
      );
      final result = await classifier.classify(window);
      expect(result.scoreOf('loud_noise'), greaterThan(0.5));
      expect(result.scoreOf('baby_cry'), lessThan(0.1));
    });

    test('classifies silence as no signal', () async {
      final classifier = MockAudioEventClassifier();
      final window = AudioWindow(
        timestamp: base,
        samples: SimulatedAudioSource.generateWindow(AudioScene.silence),
        sampleRate: SimulatedAudioSource.sampleRate,
      );
      final result = await classifier.classify(window);
      expect(result.scoreOf('baby_cry'), lessThan(0.1));
      expect(result.scoreOf('glass'), lessThan(0.1));
      expect(result.scoreOf('loud_noise'), lessThan(0.1));
    });
  });
}