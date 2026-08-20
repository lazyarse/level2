import 'dart:async';
import 'dart:typed_data';

import 'package:image/image.dart' as img;

import '../core/camera_session.dart';
import '../core/models.dart';

class SimulatedCameraSession implements CameraSession {
  @override
  String get cameraId => 'simulated';

  CameraConfig? _config;
  StreamController<AnalysisFrame>? _controller;
  Timer? _timer;
  int _step = 0;
  bool animate = true;

  @override
  Future<void> init(CameraConfig config) async {
    _config = config;
    _controller?.close();
    final controller = StreamController<AnalysisFrame>.broadcast();
    _controller = controller;
    final period = Duration(microseconds: (1000000 / config.analysisFps).round());
    _timer = Timer.periodic(period, (_) {
      final gray = generateFrame(_step++, config.analysisWidth, config.analysisHeight, animate);
      final bgr = Uint8List(gray.width * gray.height * 3);
      for (var i = 0; i < gray.gray.length; i++) {
        final v = gray.gray[i];
        bgr[i * 3] = v;
        bgr[i * 3 + 1] = v;
        bgr[i * 3 + 2] = v;
      }
      final frame = AnalysisFrame(
        timestamp: DateTime.now(),
        bitmap: gray,
        color: ColorBitmap(gray.width, gray.height, bgr),
      );
      controller.add(frame);
    });
  }

  static GrayscaleBitmap generateFrame(
      int step, int width, int height, bool animate) {
    final gray = Uint8List(width * height)..fillRange(0, width * height, 140);
    if (animate) {
      final rectW = width ~/ 4;
      final rectH = height ~/ 3;
      final x = (step * (width ~/ 20)) % (width - rectW);
      final y = (step * (height ~/ 30)) % (height - rectH);
      for (var yy = y; yy < y + rectH; yy++) {
        for (var xx = x; xx < x + rectW; xx++) {
          gray[yy * width + xx] = 40;
        }
      }
    }
    return GrayscaleBitmap(width, height, gray);
  }

  @override
  Stream<AnalysisFrame> get analysisFrames =>
      _controller?.stream ?? const Stream.empty();

  @override
  Future<Snapshot> takeSnapshot() async {
    final config = _config!;
    final frame = generateFrame(_step, config.analysisWidth, config.analysisHeight, animate);
    final image = img.Image(width: frame.width, height: frame.height, numChannels: 3);
    for (var y = 0; y < frame.height; y++) {
      for (var x = 0; x < frame.width; x++) {
        final v = frame.pixel(x, y);
        image.setPixelRgb(x, y, v, v, v);
      }
    }
    final bytes = Uint8List.fromList(img.encodePng(image));
    final name = 'snap-${DateTime.now().microsecondsSinceEpoch}.png';
    return Snapshot(bytes: bytes, mimeType: 'image/png', name: name);
  }

  @override
  Future<void> dispose() async {
    _timer?.cancel();
    await _controller?.close();
  }
}