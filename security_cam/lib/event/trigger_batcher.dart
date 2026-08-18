import 'dart:async';

import '../core/models.dart';

class TriggerBatch {
  final DateTime timestamp;
  final List<TriggerEvent> triggers;
  final Snapshot? snapshot;
  final String? videoName;

  TriggerBatch({
    required this.timestamp,
    required this.triggers,
    this.snapshot,
    this.videoName,
  });
}

class TriggerBatcher {
  final Duration window;
  final Future<Snapshot?> Function() captureSnapshot;

  /// Optional video clip capture started on the first trigger of a batch
  /// (Android only; null elsewhere). Receives the trigger timestamp so the
  /// native side can bound the pre-roll ring buffer, and resolves to the clip
  /// display name (or null) once the post-roll tail is recorded.
  final Future<String?> Function(DateTime triggerAt)? captureVideo;

  final StreamController<TriggerBatch> _batches =
      StreamController<TriggerBatch>.broadcast();
  DateTime? _openedAt;
  List<TriggerEvent> _pending = [];
  Future<Snapshot?>? _pendingSnapshot;
  Future<String?>? _pendingVideo;
  Timer? _timer;
  bool _disposed = false;

  TriggerBatcher({
    required this.window,
    required this.captureSnapshot,
    this.captureVideo,
  });

  Stream<TriggerBatch> get batches => _batches.stream;

  void add(TriggerEvent event) {
    if (_disposed) return;
    if (_pending.isEmpty) {
      _openedAt = event.timestamp;
      _pendingSnapshot = _captureSnapshotSafely();
      _pendingVideo = _captureVideoSafely(event.timestamp);
      _timer = Timer(window, () => unawaited(_flush()));
    }
    _pending.add(event);
  }

  Future<Snapshot?> _captureSnapshotSafely() async {
    try {
      return await captureSnapshot();
    } catch (_) {
      return null;
    }
  }

  Future<String?> _captureVideoSafely(DateTime triggerAt) async {
    final capture = captureVideo;
    if (capture == null) return null;
    try {
      return await capture(triggerAt);
    } catch (_) {
      return null;
    }
  }

  Future<void> _flush() async {
    _timer?.cancel();
    _timer = null;
    if (_pending.isEmpty) return;
    final openedAt = _openedAt!;
    final events = List<TriggerEvent>.unmodifiable(_pending);
    final snapshotFuture = _pendingSnapshot;
    final videoFuture = _pendingVideo;
    _pending = [];
    _pendingSnapshot = null;
    _pendingVideo = null;
    _openedAt = null;
    final snapshot = snapshotFuture == null ? null : await snapshotFuture;
    final videoName = videoFuture == null ? null : await videoFuture;
    _batches.add(TriggerBatch(
      timestamp: openedAt,
      triggers: events,
      snapshot: snapshot,
      videoName: videoName,
    ));
  }

  Future<void> dispose() async {
    _disposed = true;
    _timer?.cancel();
    _timer = null;
    await _batches.close();
  }
}