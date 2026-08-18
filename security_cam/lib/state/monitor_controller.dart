import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/audio_source.dart';
import '../core/camera_session.dart';
import '../core/media_naming.dart';
import '../core/models.dart';
import '../core/settings.dart';
import '../detection/pipeline.dart';
import '../event/event_pipeline.dart';
import '../event/trigger_batcher.dart';
import '../sensors/audio_source_factory.dart';
import '../sensors/android_camera_session.dart';
import '../sensors/audio_classifier_factory.dart';
import '../sensors/camera_source_factory.dart';
import '../sensors/ffmpeg_audio_source.dart';
import '../sensors/ffmpeg_camera_session.dart';
import '../sensors/permissions_service.dart';
import '../sensors/simulated_audio_source.dart';
import '../storage/event_recorder.dart';
import '../storage/settings_store.dart';
import '../storage/snapshot_store.dart';
import '../storage/video_store.dart';

enum MonitorState { idle, starting, monitoring, error }

class MonitorController extends ChangeNotifier {
  static const defaultPurgeInterval = Duration(hours: 6);

  final SettingsStore settingsStore;
  final EventRecorder eventRecorder;
  final SnapshotStore snapshotStore;

  /// Video clip store (no-op on desktop; native MediaStore-backed on Android).
  final VideoStore videoStore;

  /// Retention purge cadence; `null` disables the periodic timer (tests).
  final Duration? purgeInterval;

  /// Runtime permission gate; injectable for tests (no-op on desktop).
  final PermissionsService permissionsService;

  MonitorState state = MonitorState.idle;
  AppSettings settings = AppSettings.defaults();
  String? error;

  Timer? _purgeTimer;

  MonitorController({
    required this.settingsStore,
    required this.eventRecorder,
    required this.snapshotStore,
    this.videoStore = const NoopVideoStore(),
    this.purgeInterval = defaultPurgeInterval,
    PermissionsService? permissionsService,
  }) : permissionsService = permissionsService ?? buildPermissionsService();

  CameraSession? _camera;
  AudioSource? _audio;
  DetectorPipeline? _pipeline;
  TriggerBatcher? _batcher;
  StreamSubscription<AnalysisFrame>? _frameSub;
  StreamSubscription<AudioWindow>? _audioSub;
  StreamSubscription<TriggerEvent>? _triggerSub;
  StreamSubscription<TriggerBatch>? _batchSub;
  StreamSubscription<String>? _cameraFailureSub;
  StreamSubscription<String>? _audioFailureSub;

  Future<void> init() async {
    settings = await settingsStore.load();
    _restartPurgeTimer();
    notifyListeners();
  }

  Future<void> updateSettings(AppSettings next) async {
    settings = next;
    await settingsStore.save(next);
    _restartPurgeTimer();
    notifyListeners();
  }

  /// Automatic snapshot/event retention purge: deletes rows and snapshot files
  /// older than [AppSettings.retentionDays] (0 disables). Called on init and on
  /// a periodic timer; exposed for tests and manual triggering.
  Future<void> purgeOldEvents() async {
    final days = settings.retentionDays;
    if (days <= 0) return;
    await _deleteOlderThan(DateTime.now().subtract(Duration(days: days)));
  }

  void _restartPurgeTimer() {
    _purgeTimer?.cancel();
    _purgeTimer = null;
    final interval = purgeInterval;
    if (interval == null || settings.retentionDays <= 0) return;
    unawaited(purgeOldEvents());
    _purgeTimer =
        Timer.periodic(interval, (_) => unawaited(purgeOldEvents()));
  }

  Future<void> clearEvents({Duration? olderThan}) async {
    final cutoff =
        olderThan == null ? null : DateTime.now().subtract(olderThan);
    await _deleteOlderThan(cutoff);
  }

  Future<void> _deleteOlderThan(DateTime? cutoff) async {
    final deleted = await eventRecorder.deleteEvents(olderThan: cutoff);
    for (final name in deleted.snapshotNames) {
      try {
        await snapshotStore.delete(name);
      } catch (_) {}
    }
    for (final name in deleted.videoNames) {
      try {
        await videoStore.delete(name);
      } catch (_) {}
    }
  }

  /// Opens a recorded clip in the external system player (no-op on desktop).
  Future<void> openVideo(String name) => videoStore.open(name);

  Stream<AnalysisFrame>? get analysisFrames => _camera?.analysisFrames;

  AudioScene get audioScene =>
      _audio is SimulatedAudioSource
          ? (_audio as SimulatedAudioSource).scene
          : AudioScene.babyCry;

  void setAudioScene(AudioScene scene) {
    if (_audio is SimulatedAudioSource) {
      (_audio as SimulatedAudioSource).scene = scene;
    }
    notifyListeners();
  }

  Future<void> start() async {
    if (state == MonitorState.monitoring) return;
    final permissions = await permissionsService.ensurePermissions();
    if (!permissions.monitorGranted) {
      state = MonitorState.error;
      error = 'Camera and microphone permissions are required to monitor — '
          'grant them in system Settings and try again.';
      notifyListeners();
      return;
    }
    state = MonitorState.starting;
    error = null;
    notifyListeners();
    try {
      final camera = buildCameraSession(settings);
      final audio = buildAudioSource(settings);
      _audioFailureSub = audio is FfmpegAudioSource
          ? audio.failures.listen((message) {
              unawaited(_failToError(message));
            })
          : null;
      final pipeline = DetectorPipeline(
        classifier: await buildAudioClassifier(),
        configs: settings.detectorConfigs.values.toList(),
      );
      await pipeline.init();
      await camera.init(CameraConfig(
        cameraId: camera.cameraId,
        analysisWidth: 160,
        analysisHeight: 120,
        analysisFps: 4,
      ));
      final cameraFailures = camera is FfmpegCameraSession
          ? camera.failures
          : camera is AndroidCameraSession
              ? camera.failures
              : null;
      _cameraFailureSub = cameraFailures?.listen((message) {
        unawaited(_failToError(message));
      });

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
        captureSnapshot: () async {
          final snap = await camera.takeSnapshot();
          final ext = snap.mimeType == 'image/png' ? 'png' : 'jpg';
          return Snapshot(
            bytes: snap.bytes,
            mimeType: snap.mimeType,
            name: mediaFileName(
              timestamp: DateTime.now(),
              cameraName: settings.cameraName,
              extension: ext,
            ),
          );
        },
        captureVideo: (triggerAt) => videoStore.exportClip(
          triggerAt: triggerAt,
          cameraName: settings.cameraName,
          preRollSeconds: settings.preRollSeconds,
          postRollSeconds: settings.postRollSeconds,
        ),
      );
      _batcher = batcher;
      _batchSub = batcher.batches.listen((batch) {
        unawaited(eventPipeline.handleBatch(batch));
      });
      _triggerSub = pipeline.triggers.listen(batcher.add);
      _frameSub = camera.analysisFrames.listen((frame) {
        unawaited(pipeline.processFrame(frame));
      });
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

  Future<void> _failToError(String message) async {
    await _disposeRuntime();
    state = MonitorState.error;
    error = message;
    notifyListeners();
  }

  Future<void> _disposeRuntime() async {
    await _audioSub?.cancel();
    await _frameSub?.cancel();
    await _triggerSub?.cancel();
    await _batchSub?.cancel();
    await _cameraFailureSub?.cancel();
    await _audioFailureSub?.cancel();
    _audio?.stop();
    await _audio?.dispose();
    await _pipeline?.dispose();
    await _camera?.dispose();
    await _batcher?.dispose();
    _audioSub = null;
    _frameSub = null;
    _triggerSub = null;
    _batchSub = null;
    _cameraFailureSub = null;
    _audioFailureSub = null;
    _audio = null;
    _camera = null;
    _pipeline = null;
    _batcher = null;
  }

  @override
  void dispose() {
    _purgeTimer?.cancel();
    unawaited(_disposeRuntime());
    super.dispose();
  }
}