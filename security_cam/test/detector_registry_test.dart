import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/detector.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/core/registries.dart';
import 'package:security_cam/detection/person/person_detector.dart';
import 'package:security_cam/event/event_pipeline.dart';

void main() {
  test('registry builds a PersonDetector for the person trigger', () {
    final factory = detectorRegistry[TriggerType.person];
    expect(factory, isNotNull);
    final detector = factory!(
      const DetectorConfig(type: TriggerType.person),
    );
    expect(detector, isA<PersonDetector>());
    expect(detector.triggerType, TriggerType.person);
    expect(detector.id, 'person');
  });

  test('triggerLabel renders Person', () {
    expect(triggerLabel(TriggerType.person), 'Person');
  });
}