import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import '../core/audio_source.dart';
import '../core/models.dart';

enum AudioScene { silence, babyCry, glassBreak, bang }

class SimulatedAudioSource implements AudioSource {
  static const sampleRate = 16000;
  static const windowSamples = 15600;

  final StreamController<AudioWindow> _controller =
      StreamController<AudioWindow>.broadcast();
  Timer? _timer;
  AudioScene scene = AudioScene.babyCry;

  @override
  Stream<AudioWindow> get windows => _controller.stream;

  @override
  void start() {
    _timer ??= Timer.periodic(const Duration(seconds: 1), (_) {
      _controller.add(AudioWindow(
        timestamp: DateTime.now(),
        samples: generateWindow(scene),
        sampleRate: sampleRate,
      ));
    });
  }

  @override
  void stop() {
    _timer?.cancel();
    _timer = null;
  }

  static Float32List generateWindow(AudioScene scene) {
    final rng = Random(42);
    final samples = Float32List(windowSamples);
    switch (scene) {
      case AudioScene.silence:
        for (var i = 0; i < windowSamples; i++) {
          samples[i] = (rng.nextDouble() - 0.5) * 0.02;
        }
      case AudioScene.babyCry:
        final freq = 250.0;
        for (var i = 0; i < windowSamples; i++) {
          final t = i / sampleRate;
          final mod = 0.7 + 0.3 * sin(2 * pi * 4 * t);
          samples[i] = 0.45 * mod * sin(2 * pi * freq * t);
        }
      case AudioScene.glassBreak:
        for (var i = 0; i < windowSamples; i++) {
          final envelope = exp(-1 * (i / windowSamples));
          samples[i] = 0.8 * envelope * (rng.nextDouble() * 2 - 1);
        }
      case AudioScene.bang:
        for (var i = 0; i < windowSamples; i++) {
          samples[i] = rng.nextDouble() * 2 - 1;
        }
    }
    return samples;
  }

  @override
  Future<void> dispose() async {
    stop();
    await _controller.close();
  }
}