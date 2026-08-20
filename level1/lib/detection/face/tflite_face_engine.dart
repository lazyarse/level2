import 'package:face_detection_tflite/face_detection_tflite.dart' as fdt;

import '../../core/models.dart';
import 'face_engine.dart';

/// BlazeFace via `face_detection_tflite` (back-camera/full-range model — tuned
/// for distant/group faces). Runs inference in the package's background isolate.
class TfliteFaceEngine implements FaceEngine {
  TfliteFaceEngine({this.minScore = 0.0});

  final double minScore;
  fdt.FaceDetector? _detector;

  @override
  Future<void> init() async {
    _detector = await fdt.FaceDetector.create(
      model: fdt.FaceDetectionModel.backCamera,
      minScore: minScore,
    );
  }

  @override
  Future<List<FaceDetection>> detectFaces(ColorBitmap frame) async {
    final detector = _detector;
    if (detector == null) return const [];
    final faces = await detector.detectFacesFromMatBytes(frame.bgr,
        width: frame.width, height: frame.height);
    return [
      for (final f in faces)
        FaceDetection(
          box: _toBox(f.boundingBox),
          score: f.score,
        ),
    ];
  }

  FaceBox _toBox(fdt.BoundingBox bb) {
    final x = bb.topLeft.x;
    final y = bb.topLeft.y;
    return (x, y, x + bb.width, y + bb.height);
  }

  @override
  Future<void> dispose() async {
    await _detector?.dispose();
    _detector = null;
  }
}