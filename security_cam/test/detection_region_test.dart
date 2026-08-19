import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/models.dart';

void main() {
  const rect = DetectionRegion(
    id: 'r1',
    shape: 'rect',
    label: 'doorway',
    points: [0.1, 0.2, 0.5, 0.8],
  );

  test('rect JSON round-trips', () {
    final back = DetectionRegion.fromJson(rect.toJson());
    expect(back.id, 'r1');
    expect(back.shape, 'rect');
    expect(back.label, 'doorway');
    expect(back.points, [0.1, 0.2, 0.5, 0.8]);
  });

  test('poly JSON round-trips', () {
    const poly = DetectionRegion(
      id: 'p1',
      shape: 'poly',
      label: 'driveway',
      points: [0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8],
    );
    final back = DetectionRegion.fromJson(poly.toJson());
    expect(back.shape, 'poly');
    expect(back.label, 'driveway');
    expect(back.points, [0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8]);
  });
}