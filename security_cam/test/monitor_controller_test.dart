import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart' as sql;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:security_cam/sensors/simulated_audio_source.dart';
import 'package:security_cam/state/monitor_controller.dart';
import 'package:security_cam/storage/event_log.dart';
import 'package:security_cam/storage/settings_store.dart';
import 'package:security_cam/storage/snapshot_store.dart';

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
}