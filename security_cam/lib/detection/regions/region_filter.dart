import 'dart:math' as math;
import 'dart:typed_data';

import '../../core/models.dart';

/// Whether [p] is inside region [r]. Coordinates are normalized 0..1.
bool pointInRegion(DetectionRegion r, double x, double y) {
  if (r.shape == DetectionRegionShape.rect) {
    final x0 = r.points[0], y0 = r.points[1];
    final x1 = r.points[2], y1 = r.points[3];
    return x >= x0 && x <= x1 && y >= y0 && y <= y1;
  }
  return _pointInPolygon(x, y, r.points);
}

/// Ray-casting point-in-polygon over a flattened [x0,y0,x1,y1,...] list.
bool _pointInPolygon(double x, double y, List<double> pts) {
  var inside = false;
  var j = pts.length - 2;
  for (var i = 0; i < pts.length; i += 2) {
    final xi = pts[i], yi = pts[i + 1];
    final xj = pts[j], yj = pts[j + 1];
    final intersects =
        ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
    if (intersects) inside = !inside;
    j = i;
  }
  return inside;
}

/// True when box (x, y, w, h) overlaps ANY region (the "any overlap" rule).
/// Empty [regions] = whole frame → always true.
bool rectOverlapsAny(
    List<DetectionRegion> regions, double x, double y, double w, double h) {
  if (regions.isEmpty) return true;
  final corners = [
    (x, y),
    (x + w, y),
    (x, y + h),
    (x + w, y + h),
  ];
  for (final r in regions) {
    if (_boxOverlapsRegion(r, x, y, w, h, corners)) return true;
  }
  return false;
}

bool _boxOverlapsRegion(DetectionRegion r, double x, double y, double w, double h,
    List<(double, double)> corners) {
  if (r.shape == DetectionRegionShape.rect) {
    final x0 = r.points[0], y0 = r.points[1];
    final x1 = r.points[2], y1 = r.points[3];
    // Inclusive edges: a box merely touching the region's border counts as
    // overlap (matches "box merely touching a corner counts as overlap").
    return x <= x1 && x + w >= x0 && y <= y1 && y + h >= y0;
  }
  // Poly: any box corner inside the poly, OR any poly vertex inside the box,
  // OR any box edge intersecting any poly edge.
  for (final (cx, cy) in corners) {
    if (_pointInPolygon(cx, cy, r.points)) return true;
  }
  final boxX0 = x, boxY0 = y, boxX1 = x + w, boxY1 = y + h;
  for (var i = 0; i < r.points.length; i += 2) {
    final vx = r.points[i], vy = r.points[i + 1];
    if (vx >= boxX0 && vx <= boxX1 && vy >= boxY0 && vy <= boxY1) return true;
  }
  // Box edge vs poly edge intersection (cross-boundary case).
  final boxEdges = [
    ((boxX0, boxY0), (boxX1, boxY0)),
    ((boxX1, boxY0), (boxX1, boxY1)),
    ((boxX1, boxY1), (boxX0, boxY1)),
    ((boxX0, boxY1), (boxX0, boxY0)),
  ];
  var j = r.points.length - 2;
  for (var i = 0; i < r.points.length; i += 2) {
    final (xi, yi) = (r.points[i], r.points[i + 1]);
    final (xj, yj) = (r.points[j], r.points[j + 1]);
    for (final (p1, p2) in boxEdges) {
      if (_segmentsIntersect(p1, p2, (xi, yi), (xj, yj))) return true;
    }
    j = i;
  }
  return false;
}

bool _segmentsIntersect(
    (double, double) p1, (double, double) p2,
    (double, double) p3, (double, double) p4) {
  double orient((double, double) a, (double, double) b, (double, double) c) =>
      (b.$1 - a.$1) * (c.$2 - a.$2) - (b.$2 - a.$2) * (c.$1 - a.$1);

  bool onSegment((double, double) a, (double, double) b, (double, double) c) =>
      b.$1 <= math.max(a.$1, c.$1) && b.$1 >= math.min(a.$1, c.$1) &&
      b.$2 <= math.max(a.$2, c.$2) && b.$2 >= math.min(a.$2, c.$2);

  final o1 = orient(p1, p2, p3);
  final o2 = orient(p1, p2, p4);
  final o3 = orient(p3, p4, p1);
  final o4 = orient(p3, p4, p2);

  // Proper intersection: p3/p4 straddle line p1p2 AND p1/p2 straddle line p3p4.
  if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) &&
      ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) {
    return true;
  }
  // Collinear special cases.
  if (o1 == 0 && onSegment(p1, p3, p2)) return true;
  if (o2 == 0 && onSegment(p1, p4, p2)) return true;
  if (o3 == 0 && onSegment(p3, p1, p4)) return true;
  if (o4 == 0 && onSegment(p3, p2, p4)) return true;
  return false;
}

/// Builds a byte mask (1 = inside ANY region) and the count of 1-bits, using
/// each pixel's center for the point-in-region test. Empty [regions] → all ones.
(Uint8List, int) pixelMask(List<DetectionRegion> regions, int width, int height) {
  final mask = Uint8List(width * height);
  if (regions.isEmpty) {
    mask.fillRange(0, mask.length, 1);
    return (mask, mask.length);
  }
  var count = 0;
  for (var y = 0; y < height; y++) {
    for (var x = 0; x < width; x++) {
      final nx = (x + 0.5) / width;
      final ny = (y + 0.5) / height;
      var inside = false;
      for (final r in regions) {
        if (pointInRegion(r, nx, ny)) {
          inside = true;
          break;
        }
      }
      if (inside) {
        mask[y * width + x] = 1;
        count++;
      }
    }
  }
  return (mask, count);
}