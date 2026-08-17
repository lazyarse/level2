import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';

import 'package:security_cam/sensors/android_camera_session.dart';

void main() {
  test('parseFrameEvent builds an AnalysisFrame from a valid payload', () {
    final gray = Uint8List.fromList(List<int>.generate(160 * 120, (i) => i % 256));
    final frame = AndroidCameraSession.parseFrameEvent({
      'width': 160,
      'height': 120,
      'gray': gray,
    });
    expect(frame, isNotNull);
    expect(frame!.bitmap.width, 160);
    expect(frame.bitmap.height, 120);
    expect(frame.bitmap.gray, gray);
  });

  test('parseFrameEvent rejects malformed payloads', () {
    expect(AndroidCameraSession.parseFrameEvent(null), isNull);
    expect(AndroidCameraSession.parseFrameEvent('nope'), isNull);
    expect(
      AndroidCameraSession.parseFrameEvent({'width': 1, 'height': 1}),
      isNull,
    );
    expect(
      AndroidCameraSession.parseFrameEvent({
        'width': 160,
        'height': 120,
        'gray': Uint8List.fromList([1, 2, 3]),
      }),
      isNull,
      reason: 'gray length must match width*height',
    );
  });
}