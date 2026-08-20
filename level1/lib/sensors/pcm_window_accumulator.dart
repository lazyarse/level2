import 'dart:typed_data';

import '../core/models.dart';

/// Accumulates raw 16 kHz s16le PCM byte chunks (as produced by
/// `ffmpeg ... -ar 16000 -ac 1 -sample_fmt s16le -f s16le pipe:1`) into
/// [AudioWindow]s, converting to `Float32` samples (`sample / 32768`) and
/// carrying any partial window across chunk boundaries.
class PcmWindowAccumulator {
  final int sampleRate;
  final int windowSamples;
  final BytesBuilder _pending = BytesBuilder();

  PcmWindowAccumulator({
    this.sampleRate = 16000,
    this.windowSamples = 15600,
  });

  List<AudioWindow> add(Uint8List chunk) {
    _pending.add(chunk);
    final bytes = _pending.takeBytes();
    final sampleCount = bytes.length ~/ 2;
    final windows = <AudioWindow>[];
    var offset = 0;
    while (sampleCount - offset >= windowSamples) {
      windows.add(AudioWindow(
        timestamp: DateTime.now(),
        samples: _decode(bytes, offset, windowSamples),
        sampleRate: sampleRate,
      ));
      offset += windowSamples;
    }
    final consumedBytes = offset * 2;
    if (consumedBytes < bytes.length) {
      _pending.add(bytes.sublist(consumedBytes));
    }
    return windows;
  }

  Float32List _decode(Uint8List bytes, int sampleOffset, int count) {
    final out = Float32List(count);
    var p = sampleOffset * 2;
    final byteData = bytes.buffer.asByteData();
    for (var i = 0; i < count; i++) {
      out[i] = byteData.getInt16(p, Endian.little) / 32768;
      p += 2;
    }
    return out;
  }

  int get bufferedBytes => _pending.length;
}