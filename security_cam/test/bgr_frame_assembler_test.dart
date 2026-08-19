import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/sensors/bgr_frame_assembler.dart';

void main() {
  test('splits BGR chunks into whole frames carrying remainder', () {
    final a = BgrFrameAssembler(2, 2); // frame = 12 bytes
    final chunk = Uint8List.fromList(List.generate(14, (i) => i));
    final frames = a.add(chunk);
    expect(frames, hasLength(1));
    expect(frames.first.bgr, [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]);
    expect(a.buffered, 2);
    final tail = a.add(Uint8List.fromList([12, 13]));
    expect(tail, isEmpty);
    expect(a.buffered, 4);
    final done = a.add(Uint8List.fromList([14, 15, 16, 17, 18, 19, 20, 21]));
    expect(done, hasLength(1));
    expect(done.first.bgr, [12, 13, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21]);
  });
}
