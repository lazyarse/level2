import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:image/image.dart' as img;

import '../core/camera_session.dart';
import '../core/models.dart';
import 'bgr_frame_assembler.dart';

/// Dev-time-only camera source for desktop: feeds [ffmpeg] stdout into the
/// pipeline as raw grayscale frames.
///
/// This is NOT a production path — it is a stand-in so real webcam / recorded
/// footage can drive the pipeline during prototyping, before the native Android
/// `camera_service` module / iOS plugin land. To remove it, delete this file and
/// drop the `cameraSource`/`cameraSourcePath` settings + the factory branch in
/// `MonitorController.start` (mobile sources ignore those settings anyway).
class FfmpegCameraSession implements CameraSession {
  final String source;
  final String path;

  FfmpegCameraSession(this.source, this.path);

  @override
  String get cameraId => source;

  CameraConfig? _config;
  Process? _process;
  StreamController<AnalysisFrame>? _controller;
  StreamController<String>? _failures;
  StreamSubscription<List<int>>? _stdoutSub;
  BgrFrameAssembler? _colorAssembler;
  GrayscaleBitmap? _latest;
  final StringBuffer _stderrTail = StringBuffer();
  bool _disposed = false;

  /// Async ffmpeg failures (bad device/path, driver errors) surfaced as readable
  /// messages. The controller transitions to [MonitorState.error] on these.
  Stream<String> get failures =>
      _failures?.stream ?? const Stream<String>.empty();

  /// Dev-time-only argv builders (pure, unit-tested).
  static List<String> buildArgs({
    required String source,
    required String path,
    required int width,
    required int height,
    required int fps,
  }) {
    final scale = 'scale=$width:$height';
    final encode = ['-vf', scale, '-pix_fmt', 'bgr24', '-f', 'rawvideo', 'pipe:1'];
    return switch (source) {
      'webcam' => [
          '-f', 'v4l2',
          '-framerate', '$fps',
          '-i', path,
          ...encode,
        ],
      'file' => [
          '-re',
          '-stream_loop', '-1',
          '-i', path,
          ...encode,
        ],
      _ => throw ArgumentError.value(source, 'source', 'unsupported'),
    };
  }

  @override
  Future<void> init(CameraConfig config) async {
    _config = config;
    _controller?.close();
    _failures?.close();
    _colorAssembler = BgrFrameAssembler(config.analysisWidth, config.analysisHeight);
    final controller = StreamController<AnalysisFrame>.broadcast();
    final failures = StreamController<String>.broadcast();
    _controller = controller;
    _failures = failures;

    final process = await Process.start(
      'ffmpeg',
      buildArgs(
        source: source,
        path: path,
        width: config.analysisWidth,
        height: config.analysisHeight,
        fps: config.analysisFps,
      ),
      runInShell: false,
    );
    _process = process;

    process.exitCode.then((code) {
      if (_disposed) return;
      if (code != 0 && !failures.isClosed) {
        failures.add('ffmpeg exited with code $code'
            '${_stderrTail.isEmpty ? '' : ': ${_stderrTail.toString().trim()}'}');
      }
      controller.close();
    });

    process.stderr.listen((chunk) {
      if (_stderrTail.length > 2000) {
        _stderrTail.clear();
      }
      _stderrTail.write(String.fromCharCodes(chunk));
    }, onError: (_) {});

    _stdoutSub = process.stdout.listen((chunk) {
      final colorFrames = _colorAssembler!.add(Uint8List.fromList(chunk));
      for (final color in colorFrames) {
        final gray = color.toGrayscale();
        _latest = gray;
        controller.add(AnalysisFrame(
          timestamp: DateTime.now(),
          bitmap: gray,
          color: color,
        ));
      }
    }, onError: (_) {}, onDone: () {
      if (!_disposed && !controller.isClosed) {
        controller.close();
      }
    });
  }

  @override
  Stream<AnalysisFrame> get analysisFrames =>
      _controller?.stream ?? const Stream.empty();

  @override
  Future<Snapshot> takeSnapshot() async {
    final config = _config!;
    final frame = _latest ??
        GrayscaleBitmap(
            config.analysisWidth, config.analysisHeight,
            Uint8List(config.analysisWidth * config.analysisHeight));
    final image = img.Image(
        width: frame.width, height: frame.height, numChannels: 3);
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
    _disposed = true;
    await _stdoutSub?.cancel();
    final process = _process;
    if (process != null) {
      if (process.kill(ProcessSignal.sigterm)) {
        await process.exitCode.timeout(
          const Duration(seconds: 3),
          onTimeout: () {
            process.kill(ProcessSignal.sigkill);
            return process.exitCode;
          },
        );
      }
    }
    await _controller?.close();
    await _failures?.close();
    _process = null;
    _controller = null;
    _failures = null;
  }
}
