import 'dart:async';

/// Serializes [process] invocations behind a single latest-wins pending slot.
///
/// At most one [process] call is in flight at a time. Adding an input while the
/// worker is busy replaces the pending slot, dropping the previous pending
/// input. Errors from [process] are caught and routed to [onError]; the worker
/// loop always continues.
class AnalysisDispatcher<T> {
  AnalysisDispatcher({required this.process, this.onError});

  final Future<void> Function(T input) process;
  final void Function(Object error, StackTrace stackTrace)? onError;

  Object? _pending;
  bool _hasPending = false;
  bool _processing = false;
  bool _disposed = false;

  void add(T input) {
    if (_disposed) return;
    _pending = input;
    _hasPending = true;
    if (!_processing) {
      _processing = true;
      _drain();
    }
  }

  void _drain() {
    if (_disposed || !_hasPending) {
      _processing = false;
      return;
    }
    final input = _pending! as T;
    _hasPending = false;
    Future.sync(() => process(input)).then((_) {
      if (_disposed) {
        _processing = false;
        return;
      }
      _drain();
    }, onError: (Object error, StackTrace stackTrace) {
      onError?.call(error, stackTrace);
      if (_disposed) {
        _processing = false;
        return;
      }
      _drain();
    });
  }

  /// Clears the pending slot and stops the loop once in-flight work completes.
  Future<void> dispose() async {
    _disposed = true;
    _hasPending = false;
    _pending = null;
    while (_processing) {
      await Future<void>.delayed(Duration.zero);
    }
  }
}
