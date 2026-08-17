import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/camera_session.dart';
import '../core/models.dart';
import '../core/settings.dart';
import '../detection/audio/audio_classifier.dart';
import '../detection/pipeline.dart';
import '../event/event_pipeline.dart';
import '../event/trigger_batcher.dart';
import '../sensors/simulated_audio_source.dart';
import '../sensors/simulated_camera_session.dart';
import '../storage/event_recorder.dart';
import '../storage/settings_store.dart';
import '../storage/snapshot_store.dart';

enum MonitorState { idle, starting, monitoring, error }

class MonitorController extends ChangeNotifier {
  final SettingsStore settingsStore;
  final EventRecorder eventRecorder;
  final SnapshotStore snapshotStore;

  MonitorState state = MonitorState.idle;
  AppSettings settings = AppSettings.defaults();
  String? error;

  SimulatedCameraSession? _camera;
  SimulatedAudioSource? _audio;
  DetectorPipeline? _pipeline;
  TriggerBatcher? _batcher;
  StreamSubscription<AnalysisFrame>? _frameSub;
  StreamSubscription<AudioWindow>? _audioSub;
  StreamSubscription<TriggerEvent>? _triggerSub;
  StreamSubscription<TriggerBatch>? _batchSub;

  MonitorController({
    required this.settingsStore,
    required this.eventRecorder,
    required this.snapshotStore,
  });

  Future<void> init() async {
    settings = await settingsStore.load();
    notifyListeners();
  }

  Future<void> updateSettings(AppSettings next) async {
    settings = next;
    await settingsStore.save(next);
    notifyListeners();
  }

  Future<void> clearEvents({Duration? olderThan}) async {
    final cutoff =
        olderThan == null ? null : DateTime.now().subtract(olderThan);
    final names = await eventRecorder.deleteEvents(olderThan: cutoff);
    for (final name in names) {
      try {
        await snapshotStore.delete(name);
      } catch (_) {}
    }
  }

  Stream<AnalysisFrame>? get analysisFrames => _camera?.analysisFrames;

  AudioScene get audioScene => _audio?.scene ?? AudioScene.babyCry;

  void setAudioScene(AudioScene scene) {
    if (_audio != null) _audio!.scene = scene;
    notifyListeners();
  }

  Future<void> start() async {
    if (state == MonitorState.monitoring) return;
    state = MonitorState.starting;
    error = null;
    notifyListeners();
    try {
      final camera = SimulatedCameraSession();
      final audio = SimulatedAudioSource();
      final pipeline = DetectorPipeline(
        classifier: MockAudioEventClassifier(),
        configs: settings.detectorConfigs.values.toList(),
      );
      await pipeline.init();
      await camera.init(CameraConfig(
        cameraId: camera.cameraId,
        analysisWidth: 160,
        analysisHeight: 120,
        analysisFps: 4,
      ));

      final eventPipeline = EventPipeline(
        cameraSession: camera,
        cameraName: settings.cameraName,
        detectorConfigs: settings.detectorConfigs,
        channelConfigs: {
          for (final c in settings.channelConfigs) c.id: c,
        },
        recorder: eventRecorder,
        snapshotStore: snapshotStore,
      );

      final batcher = TriggerBatcher(
        window: settings.notificationMergeWindow,
        captureSnapshot: () => camera.takeSnapshot(),
      );
      _batcher = batcher;
      _batchSub = batcher.batches.listen((batch) {
        unawaited(eventPipeline.handleBatch(batch));
      });
      _triggerSub = pipeline.triggers.listen(batcher.add);
      _frameSub = camera.analysisFrames.listen(pipeline.processFrame);
      _audioSub = audio.windows.listen((window) {
        unawaited(pipeline.processAudio(window));
      });

      audio.start();
      _camera = camera;
      _audio = audio;
      _pipeline = pipeline;

      state = MonitorState.monitoring;
      notifyListeners();
    } catch (e) {
      state = MonitorState.error;
      error = e.toString();
      notifyListeners();
      await _disposeRuntime();
    }
  }

  Future<void> stop() async {
    if (state == MonitorState.idle) return;
    await _disposeRuntime();
    state = MonitorState.idle;
    notifyListeners();
  }

  Future<void> _disposeRuntime() async {
    await _audioSub?.cancel();
    await _frameSub?.cancel();
    await _triggerSub?.cancel();
    await _batchSub?.cancel();
    _audio?.stop();
    await _pipeline?.dispose();
    await _camera?.dispose();
    await _batcher?.dispose();
    _audioSub = null;
    _frameSub = null;
    _triggerSub = null;
    _batchSub = null;
    _audio = null;
    _camera = null;
    _pipeline = null;
    _batcher = null;
  }

  @override
  void dispose() {
    unawaited(_disposeRuntime());
    super.dispose();
  }
}