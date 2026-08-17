import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/sensors/gray_frame_assembler.dart';

void main() {
  test('emits a frame from a single exact-size chunk', () {
    final a = GrayFrameAssembler(2, 2);
    final frames = a.add(Uint8List.fromList([1, 2, 3, 4]));
    expect(frames.length, 1);
    expect(frames.single.gray, [1, 2, 3, 4]);
    expect(a.buffered, 0);
  });

  test('emits frames from arbitrary byte-split chunks', () {
    final a = GrayFrameAssembler(2, 2);
    final input = List<int>.generate(12, (i) => i); // 3 frames of 4 bytes
    final chunks = <Uint8List>[];
    for (var i = 0; i < input.length; i++) {
      chunks.add(Uint8List.fromList([input[i]]));
    }
    final frames = chunks.expand(a.add).toList();
    expect(frames.length, 3);
    expect(frames[0].gray, [0, 1, 2, 3]);
    expect(frames[2].gray, [8, 9, 10, 11]);
    expect(a.buffered, 0);
  });

  test('one chunk containing multiple frames emits all of them', () {
    final a = GrayFrameAssembler(2, 1);
    final frames = a.add(Uint8List.fromList([1, 2, 3, 4, 5, 6]));
    expect(frames.length, 3);
    expect(frames.map((f) => f.gray).toList(), [
      [1, 2],
      [3, 4],
      [5, 6],
    ]);
  });

  test('partial chunk carries remainder across calls', () {
    final a = GrayFrameAssembler(2, 2);
    expect(a.add(Uint8List.fromList([1, 2, 3])), isEmpty);
    expect(a.buffered, 3);
    final frames = a.add(Uint8List.fromList([4, 5]));
    expect(frames.length, 1);
    expect(frames.single.gray, [1, 2, 3, 4]);
    expect(a.buffered, 1);
    expect(a.add(Uint8List.fromList([6])), isEmpty);
    expect(a.buffered, 2);
    final rest = a.add(Uint8List.fromList([7, 8]));
    expect(rest.length, 1);
    expect(rest.single.gray, [5, 6, 7, 8]);
    expect(a.buffered, 0);
  });
}