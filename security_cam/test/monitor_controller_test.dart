import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart' as sql;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

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

    await Future<void>.delayed(const Duration(seconds: 4));

    final events = await eventLog.recent();
    final types = events.map((e) => e.triggerType).toSet();
    expect(types, contains('motion'), reason: 'events=${events.map((e) => e.triggerType).toList()}');
    expect(types, contains('baby_cry'));

    final files = snapDir.listSync().whereType<File>().toList();
    expect(files, isNotEmpty, reason: 'no snapshot files written');

    await controller.stop();
    expect(controller.state, MonitorState.idle);
    await eventLog.close();
  });
}