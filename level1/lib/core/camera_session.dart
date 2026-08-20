import 'dart:async';

import 'models.dart';

class CameraConfig {
  final String cameraId;
  final int analysisWidth;
  final int analysisHeight;
  final int analysisFps;

  const CameraConfig({
    required this.cameraId,
    this.analysisWidth = 160,
    this.analysisHeight = 120,
    this.analysisFps = 4,
  });
}

abstract class CameraSession {
  String get cameraId;

  Future<void> init(CameraConfig config);

  Stream<AnalysisFrame> get analysisFrames;

  Future<Snapshot> takeSnapshot();

  Future<void> dispose();
}
