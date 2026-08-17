import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart' as sql;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'state/monitor_controller.dart';
import 'storage/event_log.dart';
import 'storage/settings_store.dart';
import 'storage/snapshot_store.dart';
import 'ui/app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  if (Platform.isLinux || Platform.isWindows || Platform.isMacOS) {
    sqfliteFfiInit();
    sql.databaseFactory = databaseFactoryFfi;
  }

  final dir = await getApplicationSupportDirectory();
  final dbPath = p.join(dir.path, 'events.db');
  final snapDir = p.join(dir.path, 'snapshots');

  final settingsStore = await SettingsStore.open();
  final eventLog = await SqliteEventLog.open(dbPath);
  final snapshotStore = FileSnapshotStore(snapDir);

  final controller = MonitorController(
    settingsStore: settingsStore,
    eventRecorder: eventLog,
    snapshotStore: snapshotStore,
  );
  await controller.init();

  runApp(SecurityCamApp(
    controller: controller,
    eventLoader: () => eventLog.recent(limit: 200),
  ));
}