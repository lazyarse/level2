import '../../core/models.dart';

/// A detected person: bounding box (top-left x/y, bottom-right x/y) + score.
typedef PersonBox = (double, double, double, double, double);

/// Abstraction over an on-device person detector. Real impl:
/// [YoloPersonEngine]; headless/desktop tests use [MockPersonEngine].
abstract class PersonEngine {
  Future<void> init();

  /// Returns detected people in [frame]'s color bitmap. Empty list = no people.
  Future<List<PersonBox>> detectPersons(ColorBitmap frame);

  Future<void> dispose();
}