import 'dart:async';
import 'dart:typed_data';

import 'package:record/record.dart';

import '../core/audio_source.dart';
import '../core/models.dart';
import 'pcm_window_accumulator.dart';

/// On-device microphone source: `record` 16 kHz mono s16le stream
/// accumulated into 0.975 s [AudioWindow]s (15600 samples), matching the
/// YAMNet input patch. Mobile-only in practice — the audio source factory
/// returns [SimulatedAudioSource] on desktop and the mobile `camera_service`
/// sources ignore the desktop `ffmpeg` branches.
class MicAudioSource implements AudioSource {
  static const int sampleRate = 16000;
  static const int windowSamples = 15600;

  final AudioRecorder _recorder = AudioRecorder();
  final PcmWindowAccumulator _accumulator = PcmWindowAccumulator(
    sampleRate: sampleRate,
    windowSamples: windowSamples,
  );
  final StreamController<AudioWindow> _controller =
      StreamController<AudioWindow>.broadcast();
  StreamSubscription<Uint8List>? _sub;
  bool _started = false;

  @override
  Stream<AudioWindow> get windows => _controller.stream;

  @override
  void start() {
    if (_started) return;
    _started = true;
    unawaited(_startStreaming());
  }

  Future<void> _startStreaming() async {
    try {
      if (!await _recorder.hasPermission()) {
        _controller.addError(
            StateError('Microphone permission denied — enable in Settings'));
        return;
      }
      final stream = await _recorder.startStream(const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: sampleRate,
        numChannels: 1,
      ));
      _sub = stream.listen(
        (chunk) {
          for (final window in _accumulator.add(chunk)) {
            _controller.add(window);
          }
        },
        onError: (Object e, StackTrace st) => _controller.addError(e, st),
        onDone: () {
          if (!_controller.isClosed) _controller.close();
        },
      );
    } catch (e) {
      _controller.addError(e);
    }
  }

  @override
  void stop() {
    _started = false;
    final sub = _sub;
    _sub = null;
    sub?.cancel();
    unawaited(_recorder.stop());
  }

  @override
  Future<void> dispose() async {
    stop();
    if (!_controller.isClosed) await _controller.close();
  }
}