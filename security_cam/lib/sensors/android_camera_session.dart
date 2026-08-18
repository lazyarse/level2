import 'dart:async';

import 'package:flutter/services.dart';

import '../core/camera_session.dart';
import '../core/models.dart';

/// Android camera session backed by the native `camera_service` module:
/// a foreground `LifecycleService` owning CameraX, so analysis frames keep
/// streaming with the screen off / Activity stopped.
///
/// Frames cross via EventChannel as grayscale bytes; stills via MethodChannel
/// (native JPEG, orientation handled by CameraX).
class AndroidCameraSession implements CameraSession {
  static const String _cameraChannel = 'io.securitycam.security_cam/camera';
  static const String _framesChannel = 'io.securitycam.security_cam/frames';

  final MethodChannel _method;
  final EventChannel _events;

  @override
  final String cameraId;

  /// Clip configuration forwarded to the native ring buffer (see
  /// `startMonitoring`); snapshots get the same naming scheme via
  /// [cameraName].
  final String cameraName;
  final int preRollSeconds;
  final int postRollSeconds;

  AndroidCameraSession({
    this.cameraId = 'back',
    this.cameraName = 'Hallway',
    this.preRollSeconds = 5,
    this.postRollSeconds = 5,
    MethodChannel? method,
    EventChannel? events,
  })  : _method = method ?? const MethodChannel(_cameraChannel),
        _events = events ?? const EventChannel(_framesChannel);

  StreamController<AnalysisFrame>? _controller;
  StreamController<String>? _failures;
  StreamSubscription<dynamic>? _frameSub;
  bool _started = false;

  /// Async camera failures (permission denied, CameraX bind errors) surfaced as
  /// readable messages. The controller transitions to [MonitorState.error] on
  /// these.
  Stream<String> get failures =>
      _failures?.stream ?? const Stream<String>.empty();

  @override
  Future<void> init(CameraConfig config) async {
    _controller?.close();
    _failures?.close();
    _controller = StreamController<AnalysisFrame>.broadcast();
    _failures = StreamController<String>.broadcast();

    _frameSub = _events.receiveBroadcastStream().listen((event) {
      final frame = parseFrameEvent(event);
      if (frame != null) _controller!.add(frame);
    }, onError: (Object error) {
      if (!_failures!.isClosed) {
        _failures!.add('Camera stream error: $error');
      }
    });

    try {
      await _method.invokeMethod<void>('startMonitoring', {
        'cameraId': cameraId,
        'cameraName': cameraName,
        'preRollSeconds': preRollSeconds,
        'postRollSeconds': postRollSeconds,
      });
      _started = true;
    } on PlatformException catch (e) {
      if (!_failures!.isClosed) {
        _failures!.add(e.message ?? 'Failed to start camera service');
      }
    }
  }

  @override
  Stream<AnalysisFrame> get analysisFrames =>
      _controller?.stream ?? const Stream.empty();

  @override
  Future<Snapshot> takeSnapshot() async {
    final bytes = await _method.invokeMethod<Uint8List>('captureStill');
    if (bytes == null) {
      throw StateError('captureStill returned no image');
    }
    final name = 'snap-${DateTime.now().microsecondsSinceEpoch}.jpg';
    return Snapshot(bytes: bytes, mimeType: 'image/jpeg', name: name);
  }

  @override
  Future<void> dispose() async {
    if (_started) {
      try {
        await _method.invokeMethod<void>('stopMonitoring');
      } on PlatformException {
        // service may already be gone; ignore
      }
      _started = false;
    }
    await _frameSub?.cancel();
    await _controller?.close();
    await _failures?.close();
    _frameSub = null;
    _controller = null;
    _failures = null;
  }

  /// Parses an EventChannel frame event into an [AnalysisFrame], or null when
  /// the payload is malformed. Pure + unit-tested.
  static AnalysisFrame? parseFrameEvent(Object? event) {
    if (event is! Map) return null;
    final width = event['width'];
    final height = event['height'];
    final gray = event['gray'];
    if (width is! int || height is! int || gray is! List<int>) return null;
    if (gray.length != width * height) return null;
    return AnalysisFrame(
      timestamp: DateTime.now(),
      bitmap: GrayscaleBitmap(width, height, Uint8List.fromList(gray)),
    );
  }
}