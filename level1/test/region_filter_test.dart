import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/detection/regions/region_filter.dart';

void main() {
  const rect = DetectionRegion(
      id: 'r1', shape: 'rect', label: 'doorway', points: [0.1, 0.2, 0.5, 0.8]);

  const poly = DetectionRegion(
      id: 'p1',
      shape: 'poly',
      label: 'driveway',
      points: [0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8]);

  group('pointInRegion', () {
    test('rect contains interior point', () {
      expect(pointInRegion(rect, 0.3, 0.5), isTrue);
    });

    test('rect excludes outside point', () {
      expect(pointInRegion(rect, 0.7, 0.5), isFalse);
      expect(pointInRegion(rect, 0.3, 0.9), isFalse);
    });

    test('rect bounds are inclusive', () {
      expect(pointInRegion(rect, 0.1, 0.2), isTrue);
      expect(pointInRegion(rect, 0.5, 0.8), isTrue);
    });

    test('convex poly contains interior point', () {
      expect(pointInRegion(poly, 0.7, 0.45), isTrue);
    });

    test('poly excludes outside point', () {
      expect(pointInRegion(poly, 0.6, 0.1), isFalse);
    });

    test('concave poly ray-casting', () {
      // L-shaped poly: the notch area must be outside.
      const l = DetectionRegion(
          id: 'l1',
          shape: 'poly',
          label: 'L',
          points: [0.2, 0.2, 0.8, 0.2, 0.8, 0.4, 0.5, 0.4, 0.5, 0.8, 0.2, 0.8]);
      expect(pointInRegion(l, 0.3, 0.6), isTrue);  // inside the L
      expect(pointInRegion(l, 0.65, 0.6), isFalse); // in the notch
    });
  });

  group('rectOverlapsAny', () {
    test('empty regions = whole frame (always overlap)', () {
      expect(rectOverlapsAny(const [], 0.0, 0.0, 0.1, 0.1), isTrue);
    });

    test('box fully inside a region', () {
      expect(rectOverlapsAny(const [rect], 0.2, 0.3, 0.1, 0.1), isTrue);
    });

    test('box crossing a region edge', () {
      expect(rectOverlapsAny(const [rect], 0.45, 0.7, 0.1, 0.2), isTrue);
    });

    test('box merely touching a corner counts as overlap', () {
      expect(rectOverlapsAny(const [rect], 0.5, 0.8, 0.05, 0.05), isTrue);
    });

    test('box outside all regions', () {
      expect(rectOverlapsAny(const [rect], 0.7, 0.7, 0.1, 0.1), isFalse);
    });

    test('box overlapping a poly region', () {
      expect(rectOverlapsAny(const [poly], 0.6, 0.35, 0.1, 0.1), isTrue);
    });
  });

  group('pixelMask', () {
    test('all-ones mask for empty regions, pixelCount = full area', () {
      final (mask, count) = pixelMask(const [], 4, 3);
      expect(mask, List.filled(12, 1));
      expect(count, 12);
    });

    test('union of overlapping regions counted once', () {
      const a = DetectionRegion(
          id: 'a', shape: 'rect', label: 'a', points: [0.0, 0.0, 0.5, 0.5]);
      const b = DetectionRegion(
          id: 'b', shape: 'rect', label: 'b', points: [0.25, 0.25, 0.75, 0.75]);
      final (mask, count) = pixelMask(const [a, b], 4, 4);
      expect(mask, [1, 1, 0, 0, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0]);
      expect(count, 7,
          reason: 'union = 7 px (matches the mask above), overlapping a/b cells counted once');
    });
  });
}