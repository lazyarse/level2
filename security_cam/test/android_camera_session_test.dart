import 'package:flutter_test/flutter_test.dart';

import 'package:security_cam/sensors/android_camera_session.dart';

void main() {
  test('parseFrameEvent reads bgr and derives gray', () {
    // 1x1 pixel, BGR = [10, 20, 30] (blue, green, red).
    final frame = AndroidCameraSession.parseFrameEvent({
      'width': 1,
      'height': 1,
      'bgr': [10, 20, 30],
    });
    expect(frame, isNotNull);
    // Luminance of (r=30, g=20, b=10): 0.299*30 + 0.587*20 + 0.114*10 ≈ 22.0
    expect(frame!.bitmap.pixel(0, 0), 22);
    expect(frame.color, isNotNull);
    expect(frame.color!.r(0, 0), 30);
  });

  test('parseFrameEvent rejects malformed bgr length', () {
    expect(AndroidCameraSession.parseFrameEvent(null), isNull);
    expect(AndroidCameraSession.parseFrameEvent('nope'), isNull);
    expect(
      AndroidCameraSession.parseFrameEvent({'width': 1, 'height': 1}),
      isNull,
    );
    expect(
      AndroidCameraSession.parseFrameEvent({
        'width': 1,
        'height': 1,
        'bgr': [10, 20],
      }),
      isNull,
    );
  });
}