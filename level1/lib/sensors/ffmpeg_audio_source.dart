import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import '../core/audio_source.dart';
import '../core/models.dart';
import 'pcm_window_accumulator.dart';

/// Dev-time-only audio source for desktop: feeds [ffmpeg] stdout (16 kHz mono
/// s16le PCM) into the pipeline as [AudioWindow]s.
///
/// Not a production path — a stand-in so real microphone / recorded audio can
/// drive the pipeline during prototyping, before the native Android module / iOS
/// plugin land. To remove it, delete this file and drop the
/// `audioSource`/`audioSourcePath` settings + the factory branch in
/// `MonitorController.start` (mobile sources ignore those settings anyway).
class FfmpegAudioSource implements AudioSource {
  final String source;
  final String path;

  FfmpegAudioSource(this.source, this.path);

  final StreamController<AudioWindow> _controller =
      StreamController<AudioWindow>.broadcast();
  final StreamController<String> _failures =
      StreamController<String>.broadcast();

  Process? _process;
  StreamSubscription<List<int>>? _stdoutSub;
  PcmWindowAccumulator? _accumulator;
  bool _disposed = false;

  @override
  Stream<AudioWindow> get windows => _controller.stream;

  /// Async ffmpeg failures (missing binary, bad device/path) surfaced as readable
  /// messages. The controller transitions to [MonitorState.error] on these.
  Stream<String> get failures => _failures.stream;

  /// Dev-time-only argv builders (pure, unit-tested).
  static List<String> buildArgs({
    required String source,
    required String path,
  }) {
    final encode = [
      '-ar', '16000',
      '-ac', '1',
      '-f', 's16le',
      'pipe:1',
    ];
    return switch (source) {
      'mic' => [
          '-f', 'pulse',
          '-i', 'default',
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
  void start() {
    if (_process != null || _disposed) return;
    unawaited(_spawn());
  }

  Future<void> _spawn() async {
    try {
      final process = await Process.start(
        'ffmpeg',
        buildArgs(source: source, path: path),
        runInShell: false,
      );
      if (_disposed) {
        process.kill(ProcessSignal.sigkill);
        return;
      }
      _process = process;
      _accumulator = PcmWindowAccumulator();

      process.exitCode.then((code) {
        if (_disposed) return;
        if (code != 0 && !_failures.isClosed) {
          _failures.add('ffmpeg exited with code $code'
              '${_stderrTail.isEmpty ? '' : ': ${_stderrTail.toString().trim()}'}');
        }
        _controller.close();
      });

      process.stderr.listen((chunk) {
        if (_stderrTail.length > 2000) _stderrTail.clear();
        _stderrTail.write(String.fromCharCodes(chunk));
      }, onError: (_) {});

      _stdoutSub = process.stdout.listen((chunk) {
        final windows = _accumulator!.add(Uint8List.fromList(chunk));
        for (final window in windows) {
          _controller.add(window);
        }
      }, onError: (_) {}, onDone: () {
        if (!_disposed && !_controller.isClosed) {
          _controller.close();
        }
      });
    } catch (e) {
      if (!_disposed && !_failures.isClosed) {
        _failures.add('ffmpeg failed to start: $e');
      }
    }
  }

  final StringBuffer _stderrTail = StringBuffer();

  @override
  void stop() {
    _disposed = true;
    unawaited(_stdoutSub?.cancel());
    final process = _process;
    if (process != null) {
      process.kill(ProcessSignal.sigterm);
      // Give it a moment, then force-kill if still alive.
      unawaited(process.exitCode.then((_) {}).timeout(
        const Duration(seconds: 2),
        onTimeout: () {
          process.kill(ProcessSignal.sigkill);
          return null;
        },
      ));
    }
    _process = null;
    _stdoutSub = null;
  }

  @override
  Future<void> dispose() async {
    stop();
    if (!_controller.isClosed) await _controller.close();
    if (!_failures.isClosed) await _failures.close();
  }
}