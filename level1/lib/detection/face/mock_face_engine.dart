import '../../core/models.dart';
import 'face_engine.dart';

/// Test/dry-run engine: returns whatever [faces] was pre-loaded with.
class MockFaceEngine implements FaceEngine {
  final List<FaceDetection> faces = [];

  @override
  Future<void> init() async {}

  @override
  Future<List<FaceDetection>> detectFaces(ColorBitmap frame) async => List.of(faces);

  @override
  Future<void> dispose() async {}
}