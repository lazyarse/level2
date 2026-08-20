import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';

import 'package:security_cam/detection/audio/audio_classifier.dart';
import 'package:security_cam/detection/audio/yamnet_audio_event_classifier.dart';

void main() {
  group('YamnetAudioEventClassifier.scoresFromClasses', () {
    test('reads baby cry from class 20', () {
      final scores = Float32List(521);
      scores[YamnetAudioEventClassifier.babyCryClass] = 0.83;
      final mapped = YamnetAudioEventClassifier.scoresFromClasses(
          scores, Float32List(15600));
      expect(mapped['baby_cry'], closeTo(0.83, 1e-6));
    });

    test('fuses glass classes by max', () {
      final scores = Float32List(521);
      scores[435] = 0.2;
      scores[464] = 0.66;
      final mapped = YamnetAudioEventClassifier.scoresFromClasses(
          scores, Float32List(15600));
      expect(mapped['glass'], closeTo(0.66, 1e-6));
    });

    test('empty/zero scores map to zeros', () {
      final mapped = YamnetAudioEventClassifier.scoresFromClasses(
          Float32List(521), Float32List(15600));
      expect(mapped['baby_cry'], 0.0);
      expect(mapped['glass'], 0.0);
    });

    test('loud_noise rises with waveform RMS', () {
      final loud = Float32List(15600)..fillRange(0, 15600, 0.9);
      final quiet = Float32List(15600)..fillRange(0, 15600, 0.01);
      final m1 = YamnetAudioEventClassifier.scoresFromClasses(
          Float32List(521), loud);
      final m2 = YamnetAudioEventClassifier.scoresFromClasses(
          Float32List(521), quiet);
      expect(m1['loud_noise']!, greaterThan(m2['loud_noise']!));
    });

    test('glass survives class index out of range', () {
      final scores = Float32List(400);
      final mapped = YamnetAudioEventClassifier.scoresFromClasses(scores,
          Float32List(15600));
      expect(mapped['glass'], 0.0);
    });
  });

  group('quantization round-trips', () {
    test('int8 input write -> read preserves values within quantization error',
        () {
      final logMel = Float32List.fromList(
          [0.001, 0.5, 1.0, 3.5, 8.0, -2.0, 9.0, 0.0]);
      const scale = 0.078125; // 1/12.8, typical int8 input scale
      const zeroPoint = 0;
      final bytes = Uint8List(logMel.length);
      YamnetAudioEventClassifier.writeInput(bytes, logMel,
          int8: true, scale: scale, zeroPoint: zeroPoint);
      for (var i = 0; i < logMel.length; i++) {
        final expected = (logMel[i] / scale + zeroPoint).round();
        expect(bytes[i].toSigned(8), expected,
            reason: 'quantized value at $i');
      }
      final back = YamnetAudioEventClassifier.readOutput(bytes,
          int8: true, scale: scale, zeroPoint: zeroPoint);
      for (var i = 0; i < logMel.length; i++) {
        expect(back[i], closeTo(logMel[i], scale / 2 + 1e-6));
      }
    });

    test('float32 input write -> read is exact', () {
      final logMel = Float32List.fromList([0.001, 0.5, 3.5, -8.0, 12.0]);
      final bytes = Uint8List(logMel.length * 4);
      YamnetAudioEventClassifier.writeInput(bytes, logMel,
          int8: false, scale: 1, zeroPoint: 0);
      final back = YamnetAudioEventClassifier.readOutput(bytes,
          int8: false, scale: 1, zeroPoint: 0);
      expect(back, logMel);
    });

    test('clamps int8 values to [-128, 127]', () {
      final logMel = Float32List.fromList([1e6, -1e6]);
      const scale = 0.1;
      final bytes = Uint8List(logMel.length);
      YamnetAudioEventClassifier.writeInput(bytes, logMel,
          int8: true, scale: scale, zeroPoint: 0);
      expect(bytes[0].toSigned(8), 127);
      expect(bytes[1].toSigned(8), -128);
    });
  });

  group('AudioEventScores', () {
    test('scoreOf falls back to 0 for unknown labels', () {
      final scores = AudioEventScores(
        timestamp: DateTime(2026),
        classScores: const {'baby_cry': 0.9},
      );
      expect(scores.scoreOf('baby_cry'), 0.9);
      expect(scores.scoreOf('glass'), 0.0);
    });
  });
}