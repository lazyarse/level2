import '../../core/models.dart';

/// A detected face: bounding box (top-left x/y, bottom-right x/y) + score.
typedef FaceBox = (double, double, double, double);

/// A detected face: bounding box (top-left, bottom-right) + detector score.
class FaceDetection {
  final FaceBox box;
  final double score;

  const FaceDetection({required this.box, required this.score});
}

/// Abstraction over an on-device face detector. Real impl: [TfliteFaceEngine];
/// headless/desktop tests use [MockFaceEngine].
abstract class FaceEngine {
  Future<void> init();

  /// Returns detected faces in [frame]'s color bitmap. Empty list = no faces.
  Future<List<FaceDetection>> detectFaces(ColorBitmap frame);

  Future<void> dispose();
}