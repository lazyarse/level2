import '../../core/models.dart';
import 'person_engine.dart';

/// Test/dry-run engine: returns whatever [persons] was pre-loaded with.
class MockPersonEngine implements PersonEngine {
  final List<PersonBox> persons = [];

  @override
  Future<void> init() async {}

  @override
  Future<List<PersonBox>> detectPersons(ColorBitmap frame) async =>
      List.of(persons);

  @override
  Future<void> dispose() async {}
}