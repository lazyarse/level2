import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/sensors/pcm_window_accumulator.dart';

List<int> _s16(int value) {
  final bytes = ByteData(2);
  bytes.setInt16(0, value, Endian.little);
  return [bytes.getUint8(0), bytes.getUint8(1)];
}

void main() {
  test('decodes one window of s16le samples to Float32 in [-1, 1]', () {
    final a = PcmWindowAccumulator(sampleRate: 16000, windowSamples: 4);
    final bytes = <int>[
      ..._s16(0),
      ..._s16(16384),
      ..._s16(32767),
      ..._s16(-32768),
    ];
    final windows = a.add(Uint8List.fromList(bytes));
    expect(windows.length, 1);
    final s = windows.single.samples;
    expect(s[0], closeTo(0, 0.001));
    expect(s[1], closeTo(0.5, 0.001));
    expect(s[2], closeTo(0.99997, 0.0001));
    expect(s[3], -1.0);
    expect(windows.single.sampleRate, 16000);
    expect(a.bufferedBytes, 0);
  });

  test('arbitrary byte-split chunks still assemble full windows', () {
    final a = PcmWindowAccumulator(sampleRate: 16000, windowSamples: 2);
    final bytes = <int>[..._s16(100), ..._s16(200), ..._s16(300), ..._s16(400)];
    final received = <AudioWindow>[];
    for (final b in bytes) {
      received.addAll(a.add(Uint8List.fromList([b])));
    }
    expect(received.length, 2);
    expect(received[0].samples[0], closeTo(100 / 32768, 0.0001));
    expect(received[0].samples[1], closeTo(200 / 32768, 0.0001));
    expect(received[1].samples[0], closeTo(300 / 32768, 0.0001));
    expect(a.bufferedBytes, 0);
  });

  test('odd leftover bytes are carried across calls', () {
    final a = PcmWindowAccumulator(sampleRate: 16000, windowSamples: 3);
    expect(a.add(Uint8List.fromList(_s16(11))), isEmpty);
    expect(a.bufferedBytes, 2);
    final windows = a.add(Uint8List.fromList([..._s16(22), ..._s16(33)]));
    expect(windows.length, 1);
    expect(windows.single.samples[0], closeTo(11 / 32768, 0.0001));
    expect(windows.single.samples[2], closeTo(33 / 32768, 0.0001));
    expect(a.bufferedBytes, 0);
  });
}