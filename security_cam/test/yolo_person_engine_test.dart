import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/detection/person/yolo_person_engine.dart';

void main() {
  const anchors = 8400;
  const classes = 80;

  Float32List blankOutput() => Float32List((classes + 4) * anchors);

  Float32List personAt(int i, {double score = 0.9}) {
    final out = blankOutput();
    // cx, cy, w, h normalized [0,1]; person score in row 4.
    out[i] = 0.5;
    out[anchors + i] = 0.5;
    out[2 * anchors + i] = 0.4;
    out[3 * anchors + i] = 0.8;
    out[4 * anchors + i] = score;
    return out;
  }

  group('letterboxInfo', () {
    test('square frame maps 1:1 with no padding', () {
      final info = letterboxInfo(640, 640);
      expect(info.gain, 1);
      expect(info.padX, 0);
      expect(info.padY, 0);
    });

    test('320x240 frame scales to 640x480 with centered vertical padding', () {
      final info = letterboxInfo(320, 240);
      expect(info.gain, 2);
      expect(info.padX, 0);
      expect(info.padY, 80);
    });
  });

  group('decodeYolo26', () {
    test('decodes one anchored person into frame coordinates', () {
      final boxes = decodeYolo26(
        personAt(0),
        conf: 0.25,
        iou: 0.7,
        maxDetections: 30,
        frameWidth: 640,
        frameHeight: 640,
      );
      expect(boxes, hasLength(1));
      final b = boxes.single;
      expect(b.$1, closeTo(192, 0.01)); // (0.5 - 0.2) * 640
      expect(b.$2, closeTo(64, 0.01)); // (0.5 - 0.4) * 640
      expect(b.$3, closeTo(448, 0.01)); // (0.5 + 0.2) * 640
      expect(b.$4, closeTo(576, 0.01)); // (0.5 + 0.4) * 640
      expect(b.$5, closeTo(0.9, 1e-6));
    });

    test('undoes letterbox padding and scale back to the original frame', () {
      final out = blankOutput();
      out[0] = 0.5; // cx
      out[anchors] = 0.5; // cy
      out[2 * anchors] = 0.3; // w
      out[3 * anchors] = 0.3; // h
      out[4 * anchors] = 0.9;
      final boxes = decodeYolo26(
        out,
        conf: 0.25,
        iou: 0.7,
        maxDetections: 30,
        frameWidth: 320,
        frameHeight: 240,
      );
      // Box centered on the frame: (0.5*320, 0.5*240).
      final b = boxes.single;
      expect(b.$1, closeTo(112, 0.01)); // (0.35*640 - 0) / 2
      expect(b.$2, closeTo(72, 0.01)); // (0.35*640 - 80) / 2
      expect(b.$3, closeTo(208, 0.01)); // (0.65*640 - 0) / 2
      expect(b.$4, closeTo(168, 0.01)); // (0.65*640 - 80) / 2
    });

    test('drops anchors below the confidence gate', () {
      final boxes = decodeYolo26(
        personAt(0, score: 0.2),
        conf: 0.25,
        iou: 0.7,
        maxDetections: 30,
        frameWidth: 640,
        frameHeight: 640,
      );
      expect(boxes, isEmpty);
    });

    test('clamps out-of-frame boxes', () {
      final out = blankOutput();
      out[0] = 0.9; // cx
      out[anchors] = 0.9; // cy
      out[2 * anchors] = 0.4; // w
      out[3 * anchors] = 0.4; // h
      out[4 * anchors] = 0.9;
      final boxes = decodeYolo26(
        out,
        conf: 0.25,
        iou: 0.7,
        maxDetections: 30,
        frameWidth: 320,
        frameHeight: 240,
      );
      final b = boxes.single;
      expect(b.$1, greaterThanOrEqualTo(0));
      expect(b.$2, greaterThanOrEqualTo(0));
      expect(b.$3, lessThanOrEqualTo(320));
      expect(b.$4, lessThanOrEqualTo(240));
    });
  });

  group('nms', () {
    const a = (10.0, 10.0, 100.0, 100.0, 0.9);
    const b = (12.0, 12.0, 98.0, 98.0, 0.5);
    const c = (300.0, 300.0, 400.0, 400.0, 0.7);

    test('keeps only the higher-scoring of two overlapping boxes', () {
      final kept = nms([a, b], iou: 0.7, maxDetections: 30);
      expect(kept, hasLength(1));
      expect(kept.single.$5, 0.9);
    });

    test('keeps far-apart boxes', () {
      final kept = nms([a, c], iou: 0.7, maxDetections: 30);
      expect(kept, hasLength(2));
    });

    test('respects maxDetections', () {
      final kept = nms([a, c], iou: 0.1, maxDetections: 1);
      expect(kept, hasLength(1));
    });
  });
}