import 'dart:math';
import 'dart:typed_data';

import '../../core/models.dart';

class AudioEventScores {
  final DateTime timestamp;
  final Map<String, double> classScores;

  AudioEventScores({required this.timestamp, required this.classScores});

  double scoreOf(String label) => classScores[label] ?? 0.0;
}

abstract class AudioEventClassifier {
  String get id;

  Future<void> init();

  Future<AudioEventScores> classify(AudioWindow window);

  Future<void> dispose();
}

class MockAudioEventClassifier implements AudioEventClassifier {
  @override
  String get id => 'mock';

  @override
  Future<void> init() async {}

  @override
  Future<void> dispose() async {}

  @override
  Future<AudioEventScores> classify(AudioWindow window) async {
    final rms = _rms(window.samples);
    final zcr = _zeroCrossingRate(window.samples);
    final babyCry = (rms > 0.02 && zcr < 0.08) ? _scale(rms, 0.02, 0.3) : 0.0;
    final glass = (rms > 0.08 && zcr > 0.25) ? _scale(rms, 0.08, 0.5) : 0.0;
    return AudioEventScores(
      timestamp: window.timestamp,
      classScores: {
        'baby_cry': babyCry,
        'glass': glass,
      },
    );
  }

  double _rms(Float32List samples) {
    var sum = 0.0;
    for (final s in samples) {
      sum += s * s;
    }
    return sqrt(sum / samples.length);
  }

  double _zeroCrossingRate(Float32List samples) {
    var crossings = 0;
    for (var i = 1; i < samples.length; i++) {
      if ((samples[i] >= 0) != (samples[i - 1] >= 0)) crossings++;
    }
    return crossings / samples.length;
  }

  double _scale(double value, double floor, double ceil) {
    final v = (value - floor) / (ceil - floor);
    return v.clamp(0.0, 1.0);
  }
}
