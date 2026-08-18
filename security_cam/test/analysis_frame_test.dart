import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/models.dart';

void main() {
  test('ColorBitmap exposes pixel-safe BGR access', () {
    final bgr = ColorBitmap(2, 1, Uint8List.fromList([1, 2, 3, 4, 5, 6]));
    expect(bgr.width, 2);
    expect(bgr.height, 1);
    expect(bgr.b(0, 0), 1);
    expect(bgr.g(0, 0), 2);
    expect(bgr.r(0, 0), 3);
    expect(bgr.b(1, 0), 4);
    expect(bgr.g(1, 0), 5);
    expect(bgr.r(1, 0), 6);
  });

  test('AnalysisFrame carries optional color and required bitmap', () {
    final frame = AnalysisFrame(
      timestamp: DateTime(2026, 1, 1),
      bitmap: GrayscaleBitmap(1, 1, Uint8List.fromList([10])),
      color: ColorBitmap(1, 1, Uint8List.fromList([5, 6, 7])),
    );
    expect(frame.bitmap.pixel(0, 0), 10);
    expect(frame.color!.b(0, 0), 5);
  });
}