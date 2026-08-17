import 'dart:async';

import '../core/models.dart';

class TriggerBatch {
  final DateTime timestamp;
  final List<TriggerEvent> triggers;
  final Snapshot? snapshot;

  TriggerBatch({
    required this.timestamp,
    required this.triggers,
    this.snapshot,
  });
}

class TriggerBatcher {
  final Duration window;
  final Future<Snapshot?> Function() captureSnapshot;

  final StreamController<TriggerBatch> _batches =
      StreamController<TriggerBatch>.broadcast();
  DateTime? _openedAt;
  List<TriggerEvent> _pending = [];
  Future<Snapshot?>? _pendingSnapshot;
  Timer? _timer;
  bool _disposed = false;

  TriggerBatcher({required this.window, required this.captureSnapshot});

  Stream<TriggerBatch> get batches => _batches.stream;

  void add(TriggerEvent event) {
    if (_disposed) return;
    if (_pending.isEmpty) {
      _openedAt = event.timestamp;
      _pendingSnapshot = _captureSnapshotSafely();
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

  Future<void> _flush() async {
    _timer?.cancel();
    _timer = null;
    if (_pending.isEmpty) return;
    final openedAt = _openedAt!;
    final events = List<TriggerEvent>.unmodifiable(_pending);
    final snapshotFuture = _pendingSnapshot;
    _pending = [];
    _pendingSnapshot = null;
    _openedAt = null;
    final snapshot = snapshotFuture == null ? null : await snapshotFuture;
    _batches.add(TriggerBatch(
      timestamp: openedAt,
      triggers: events,
      snapshot: snapshot,
    ));
  }

  Future<void> dispose() async {
    _disposed = true;
    _timer?.cancel();
    _timer = null;
    await _batches.close();
  }
}