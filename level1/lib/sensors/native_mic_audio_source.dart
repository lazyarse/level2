import 'dart:async';

import 'package:flutter/services.dart';

import '../core/audio_source.dart';
import '../core/models.dart';
import 'pcm_window_accumulator.dart';

/// Android microphone source backed by the native monitoring service.
///
/// The FGS owns the only AudioRecord (16 kHz mono s16le) and streams raw PCM
/// chunks over the `io.securitycam.security_cam/camera/mic_pcm` EventChannel;
/// this source accumulates them into 0.975 s [AudioWindow]s (15600 samples),
/// matching the YAMNet input patch and mirroring [MicAudioSource]'s public
/// surface so `MonitorController`/`PcmWindowAccumulator` are unchanged. Only
/// the Android factory branch returns this type.
class NativeMicAudioSource implements AudioSource {
  static const int sampleRate = 16000;
  static const int windowSamples = 15600;

  final PcmWindowAccumulator _accumulator = PcmWindowAccumulator(
    sampleRate: sampleRate,
    windowSamples: windowSamples,
  );
  final StreamController<AudioWindow> _controller =
      StreamController<AudioWindow>.broadcast();
  final EventChannel _channel =
      const EventChannel('io.securitycam.security_cam/camera/mic_pcm');
  StreamSubscription<Uint8List>? _sub;
  bool _started = false;

  @override
  Stream<AudioWindow> get windows => _controller.stream;

  @override
  void start() {
    if (_started) return;
    _started = true;
    _sub = _channel.receiveBroadcastStream().cast<Uint8List>().listen(
      (chunk) {
        for (final window in _accumulator.add(chunk)) {
          _controller.add(window);
        }
      },
      onError: (Object e, StackTrace st) => _controller.addError(e, st),
    );
  }

  @override
  void stop() {
    _started = false;
    final sub = _sub;
    _sub = null;
    sub?.cancel();
  }

  @override
  Future<void> dispose() async {
    stop();
    if (!_controller.isClosed) await _controller.close();
  }
}