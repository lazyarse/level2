import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart' as sql;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:security_cam/sensors/permissions_service.dart';
import 'package:security_cam/sensors/simulated_audio_source.dart';
import 'package:security_cam/state/monitor_controller.dart';
import 'package:security_cam/storage/event_log.dart';
import 'package:security_cam/storage/event_recorder.dart';
import 'package:security_cam/storage/settings_store.dart';
import 'package:security_cam/storage/snapshot_store.dart';
import 'package:security_cam/storage/video_store.dart';

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    sql.databaseFactory = databaseFactoryFfi;
  });

  test('monitoring produces trigger events and snapshot files', () async {
    SharedPreferences.setMockInitialValues({});
    final settingsStore = await SettingsStore.open();
    final eventLog = await SqliteEventLog.open(inMemoryDatabasePath);
    final snapDir = await Directory.systemTemp.createTemp('scam_snaps');
    final snapshotStore = FileSnapshotStore(snapDir.path);

    final controller = MonitorController(
      settingsStore: settingsStore,
      eventRecorder: eventLog,
      snapshotStore: snapshotStore,
      permissionsService: const NoopPermissionsService(),
    );
    await controller.init();
    expect(controller.settings.detectorConfigs, isNotEmpty);

    await controller.start();
    expect(controller.state, MonitorState.monitoring);

    await Future<void>.delayed(const Duration(seconds: 5));

    final events = await eventLog.recent();
    final merged =
        events.where((e) => e.triggerTypes.contains('motion')).toList();
    expect(merged, isNotEmpty,
        reason: 'events=${events.map((e) => e.triggerTypes).toList()}');
    expect(merged.first.triggerType, 'merged');
    expect(merged.first.triggerTypes, contains('baby_cry'));

    final files = snapDir.listSync().whereType<File>().toList();
    expect(files, isNotEmpty, reason: 'no snapshot files written');
    expect(merged.first.snapshotName, matches(RegExp(r'^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{3}_Hallway\.(jpg|png)$')),
        reason: 'snapshots follow the date-time-cameraName scheme');

    await controller.stop();
    expect(controller.state, MonitorState.idle);
    await eventLog.close();
  });

  test('loud noise scene produces a loud_noise trigger event', () async {
    SharedPreferences.setMockInitialValues({});
    final settingsStore = await SettingsStore.open();
    final eventLog = await SqliteEventLog.open(inMemoryDatabasePath);
    final snapDir = await Directory.systemTemp.createTemp('scam_snaps');
    final snapshotStore = FileSnapshotStore(snapDir.path);

    final controller = MonitorController(
      settingsStore: settingsStore,
      eventRecorder: eventLog,
      snapshotStore: snapshotStore,
      permissionsService: const NoopPermissionsService(),
    );
    await controller.init();

    final loudNoise = controller.settings.detectorConfigs['loud_noise']!;
    await controller.updateSettings(controller.settings.copyWith(
      detectorConfigs: {
        ...controller.settings.detectorConfigs,
        'loud_noise': loudNoise.copyWith(enabled: true),
      },
    ));

    await controller.start();
    controller.setAudioScene(AudioScene.bang);
    expect(controller.state, MonitorState.monitoring);

    await Future<void>.delayed(const Duration(seconds: 5));

    final events = await eventLog.recent();
    final loud =
        events.where((e) => e.triggerTypes.contains('loud_noise')).toList();
    expect(loud, isNotEmpty,
        reason: 'events=${events.map((e) => e.triggerTypes).toList()}');

    await controller.stop();
    expect(controller.state, MonitorState.idle);
    await eventLog.close();
  });

  test('retention purge deletes old events, snapshot files, and videos',
      () async {
    SharedPreferences.setMockInitialValues({});
    final settingsStore = await SettingsStore.open();
    final eventLog = await SqliteEventLog.open(inMemoryDatabasePath);
    final snapDir = await Directory.systemTemp.createTemp('scam_ret');
    final snapshotStore = FileSnapshotStore(snapDir.path);
    final videoStore = _RecordingVideoStore();

    final oldFile = File('${snapDir.path}/old.png');
    oldFile.writeAsBytesSync([1, 2, 3]);
    final newFile = File('${snapDir.path}/new.png');
    newFile.writeAsBytesSync([4, 5, 6]);

    await eventLog.record(RecordedEvent(
      timestamp: DateTime.now().subtract(const Duration(days: 10)),
      cameraName: 'Hallway',
      triggerType: 'motion',
      score: 1.0,
      snapshotName: 'old.png',
      videoName: 'old.mp4',
    ));
    await eventLog.record(RecordedEvent(
      timestamp: DateTime.now(),
      cameraName: 'Hallway',
      triggerType: 'motion',
      score: 1.0,
      snapshotName: 'new.png',
    ));

    final controller = MonitorController(
      settingsStore: settingsStore,
      eventRecorder: eventLog,
      snapshotStore: snapshotStore,
      videoStore: videoStore,
      purgeInterval: null,
      permissionsService: const NoopPermissionsService(),
    );
    await controller.init();
    await controller.updateSettings(
        controller.settings.copyWith(retentionDays: 7));

    await controller.purgeOldEvents();

    final events = await eventLog.recent();
    expect(events.map((e) => e.snapshotName), ['new.png']);
    expect(oldFile.existsSync(), isFalse);
    expect(newFile.existsSync(), isTrue);
    expect(videoStore.deleted, ['old.mp4']);
    await eventLog.close();
    snapDir.deleteSync(recursive: true);
  });

  test('retention 0 disables the purge', () async {
    SharedPreferences.setMockInitialValues({});
    final settingsStore = await SettingsStore.open();
    final eventLog = await SqliteEventLog.open(inMemoryDatabasePath);
    final snapDir = await Directory.systemTemp.createTemp('scam_ret0');
    final snapshotStore = FileSnapshotStore(snapDir.path);

    final oldFile = File('${snapDir.path}/old.png');
    oldFile.writeAsBytesSync([1, 2, 3]);

    await eventLog.record(RecordedEvent(
      timestamp: DateTime.now().subtract(const Duration(days: 10)),
      cameraName: 'Hallway',
      triggerType: 'motion',
      score: 1.0,
      snapshotName: 'old.png',
    ));

    final controller = MonitorController(
      settingsStore: settingsStore,
      eventRecorder: eventLog,
      snapshotStore: snapshotStore,
      permissionsService: const NoopPermissionsService(),
    );
    await controller.init();
    await controller.updateSettings(
        controller.settings.copyWith(retentionDays: 0));

    await controller.purgeOldEvents();

    final events = await eventLog.recent();
    expect(events, isNotEmpty, reason: 'retention 0 must not purge');
    expect(oldFile.existsSync(), isTrue);
    await eventLog.close();
    snapDir.deleteSync(recursive: true);
  });

  test('start fails cleanly when camera permission is denied', () async {
    SharedPreferences.setMockInitialValues({});
    final settingsStore = await SettingsStore.open();
    final eventLog = await SqliteEventLog.open(inMemoryDatabasePath);
    final snapDir = await Directory.systemTemp.createTemp('scam_deny');
    final snapshotStore = FileSnapshotStore(snapDir.path);

    final controller = MonitorController(
      settingsStore: settingsStore,
      eventRecorder: eventLog,
      snapshotStore: snapshotStore,
      purgeInterval: null,
      permissionsService: const _FakePermissionsService(
        cameraGranted: false,
        microphoneGranted: true,
      ),
    );
    await controller.init();

    await controller.start();
    expect(controller.state, MonitorState.error);
    expect(controller.error, contains('permissions'));
    expect(controller.state, isNot(MonitorState.monitoring));
    await eventLog.close();
    snapDir.deleteSync(recursive: true);
  });

  test('start proceeds when permissions are granted', () async {
    SharedPreferences.setMockInitialValues({});
    final settingsStore = await SettingsStore.open();
    final eventLog = await SqliteEventLog.open(inMemoryDatabasePath);
    final snapDir = await Directory.systemTemp.createTemp('scam_grant');
    final snapshotStore = FileSnapshotStore(snapDir.path);

    final controller = MonitorController(
      settingsStore: settingsStore,
      eventRecorder: eventLog,
      snapshotStore: snapshotStore,
      purgeInterval: null,
      permissionsService: const _FakePermissionsService(),
    );
    await controller.init();

    await controller.start();
    expect(controller.state, MonitorState.monitoring);
    await controller.stop();
    await eventLog.close();
    snapDir.deleteSync(recursive: true);
  });
}

class _FakePermissionsService extends PermissionsService {
  const _FakePermissionsService({
    this.cameraGranted = true,
    this.microphoneGranted = true,
  });

  final bool cameraGranted;
  final bool microphoneGranted;

  @override
  Future<PermissionsResult> ensurePermissions() async => PermissionsResult(
        cameraGranted: cameraGranted,
        microphoneGranted: microphoneGranted,
        notificationsGranted: true,
      );
}

class _RecordingVideoStore implements VideoStore {
  final List<String> deleted = [];

  @override
  Future<String?> exportClip({
    required DateTime triggerAt,
    required String cameraName,
    required int preRollSeconds,
    required int postRollSeconds,
  }) async =>
      null;

  @override
  Future<void> delete(String name) async {
    deleted.add(name);
  }

  @override
  Future<void> open(String name) async {}

  @override
  Future<bool> exists(String name) async => false;

  @override
  Future<VideoClipInfo?> videoInfo(String name) async => null;
}