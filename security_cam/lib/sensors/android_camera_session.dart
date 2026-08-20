import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../core/camera_session.dart';
import '../core/models.dart';

/// Holds the display-oriented preview size and the rotation (in degrees,
/// clockwise) that must be applied to the texture to appear upright.
/// Rotation is 0, 90, 180, or 270.
class PreviewInfo {
  final Size size;
  final int rotationDegrees;

  const PreviewInfo(this.size, this.rotationDegrees);
}

const MethodChannel _orientationChannel =
    MethodChannel('io.securitycam.security_cam/camera');

/// Applies the requested screen orientation to the Android activity. Safe to
/// call before monitoring starts; no-op on non-Android platforms.
Future<void> applyScreenOrientation(String orientation) async {
  if (!Platform.isAndroid) return;
  try {
    await _orientationChannel.invokeMethod<void>('setOrientation', {
      'orientation': orientation,
    });
  } on PlatformException {
    // no activity attached (e.g. engine not fully up); ignore
  }
}

/// Android camera session backed by the native `camera_service` module:
/// a foreground `LifecycleService` owning CameraX, so analysis frames keep
/// streaming with the screen off / Activity stopped.
///
/// Frames cross via EventChannel as grayscale bytes; stills via MethodChannel
/// (native JPEG, orientation handled by CameraX). A CameraX `Preview` use case
/// streams the full-color camera feed into a Flutter-registered SurfaceTexture
/// for the smooth live view ([previewTextureId]).
class AndroidCameraSession implements CameraSession {
  static const String _cameraChannel = 'io.securitycam.security_cam/camera';
  static const String _framesChannel = 'io.securitycam.security_cam/frames';
  static const String _previewStatusChannel =
      'io.securitycam.security_cam/camera/preview_status';

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

  /// Whether the native ring buffer records pre/post-roll clips (see
  /// `startMonitoring`).
  final bool recordVideo;

  /// Recording resolution tier (see `VideoQuality` in `core/settings.dart`).
  final String videoQuality;

  AndroidCameraSession({
    this.cameraId = 'back',
    this.cameraName = 'Hallway',
    this.preRollSeconds = 5,
    this.postRollSeconds = 5,
    this.recordVideo = true,
    this.videoQuality = 'lowest',
    MethodChannel? method,
    EventChannel? events,
  })  : _method = method ?? const MethodChannel(_cameraChannel),
        _events = events ?? const EventChannel(_framesChannel);

  StreamController<AnalysisFrame>? _controller;
  StreamController<String>? _failures;
  StreamSubscription<dynamic>? _frameSub;
  bool _started = false;

  int? _previewTextureId;
  bool _previewActive = false;
  StreamController<bool>? _previewStatus;
  EventChannel? _previewEvents;
  StreamSubscription<dynamic>? _previewStatusSub;

  /// Texture id for the live preview passthrough, or null when the preview
  /// isn't active (not yet bound, or the device fell back to analysis-only).
  int? get previewTextureId => _previewActive ? _previewTextureId : null;

  /// Emits whether the live preview passthrough became active/inactive (i.e.
  /// CameraX bound a Preview use case to the Flutter SurfaceTexture).
  Stream<bool> get previewStatus =>
      _previewStatus?.stream ?? const Stream<bool>.empty();

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

    _previewStatus?.close();
    _previewStatus = StreamController<bool>.broadcast();
    _previewEvents = const EventChannel(_previewStatusChannel);
    _previewStatusSub = _previewEvents!.receiveBroadcastStream().listen((event) {
      if (event is bool) {
        debugPrint('previewStatus event -> $event');
        _previewActive = event;
        _previewStatus!.add(event);
      }
    }, onError: (Object _) {});

    _frameSub = _events.receiveBroadcastStream().listen((event) {
      final frame = parseFrameEvent(event);
      if (frame != null) _controller!.add(frame);
    }, onError: (Object error) {
      if (!_failures!.isClosed) {
        _failures!.add('Camera stream error: $error');
      }
    });

    await _createPreviewSurface();

    try {
      await _method.invokeMethod<void>('startMonitoring', {
        'cameraId': cameraId,
        'cameraName': cameraName,
        'preRollSeconds': preRollSeconds,
        'postRollSeconds': postRollSeconds,
        'recordVideo': recordVideo,
        'videoQuality': videoQuality,
        'analysisWidth': config.analysisWidth,
        'analysisHeight': config.analysisHeight,
      });
      _started = true;
    } on PlatformException catch (e) {
      if (!_failures!.isClosed) {
        _failures!.add(e.message ?? 'Failed to start camera service');
      }
    }
  }

  Future<void> _createPreviewSurface() async {
    _previewTextureId = null;
    _previewActive = false;
    try {
      _previewTextureId = await _method.invokeMethod<int?>('createPreviewSurface');
      debugPrint('createPreviewSurface -> $_previewTextureId');
    } on PlatformException catch (e) {
      debugPrint('createPreviewSurface failed ($e); analysis-only view');
    }
  }

  /// Display-oriented size of the live preview and the rotation (in degrees
  /// clockwise) needed to make the texture upright. Returns null when not bound.
  Future<PreviewInfo?> getPreviewInfo() async {
    try {
      final result =
          await _method.invokeMethod<Map<dynamic, dynamic>>('getPreviewSize');
      final width = result?['width'] as int?;
      final height = result?['height'] as int?;
      final rotation = result?['rotation'] as int?;
      if (width == null || height == null || width == 0 || height == 0) {
        return null;
      }
      final info = PreviewInfo(
        Size(width.toDouble(), height.toDouble()),
        rotation ?? 0,
      );
      debugPrint('getPreviewInfo -> ${info.size} rot=${info.rotationDegrees}');
      return info;
    } on PlatformException {
      return null;
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
    await _previewStatusSub?.cancel();
    await _previewStatus?.close();
    _previewStatusSub = null;
    _previewStatus = null;
    try {
      await _method.invokeMethod<void>('releasePreviewSurface');
    } on PlatformException {
      // service may already be gone; ignore
    }
    _previewTextureId = null;
    _previewActive = false;
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
    final bgr = event['bgr'];
    if (width is! int || height is! int || bgr is! List<int>) return null;
    if (bgr.length != width * height * 3) return null;
    final bgrBytes = Uint8List.fromList(bgr);
    return AnalysisFrame(
      timestamp: DateTime.now(),
      bitmap: ColorBitmap(width, height, bgrBytes).toGrayscale(),
      color: ColorBitmap(width, height, bgrBytes),
    );
  }
}