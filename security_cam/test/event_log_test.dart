import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart' as sql;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:security_cam/storage/event_log.dart';
import 'package:security_cam/storage/event_recorder.dart';

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    sql.databaseFactory = databaseFactoryFfi;
  });

  test('v1 database migrates to v2 adding trigger_types', () async {
    final dir = await Directory.systemTemp.createTemp('scam_db');
    final path = p.join(dir.path, 'events.db');

    final db = await sql.openDatabase(
      path,
      version: 1,
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TEXT NOT NULL,
            camera_name TEXT NOT NULL,
            trigger_type TEXT NOT NULL,
            score REAL NOT NULL,
            snapshot_name TEXT,
            channel_statuses TEXT
          )
        ''');
      },
    );
    await db.insert('events', {
      'timestamp': DateTime(2026, 1, 1, 12).toIso8601String(),
      'camera_name': 'Hallway',
      'trigger_type': 'motion',
      'score': 0.8,
      'channel_statuses': '{}',
    });
    await db.close();

    final log = await SqliteEventLog.open(path);
    final rows = await log.recent();
    expect(rows, hasLength(1));
    expect(rows.single.triggerType, 'motion');
    expect(rows.single.triggerTypes, isEmpty);

    final migrated = await sql.openDatabase(path);
    final columns = await migrated.rawQuery('PRAGMA table_info(events)');
    final names = columns.map((c) => c['name']).toList();
    expect(names, contains('trigger_types'));

    await log.close();
    await migrated.close();
    await dir.delete(recursive: true);
  });

  test('merged triggerTypes round-trip at schema v2', () async {
    final log = await SqliteEventLog.open(inMemoryDatabasePath);
    await log.record(RecordedEvent(
      timestamp: DateTime(2026, 1, 1, 12),
      cameraName: 'Hallway',
      triggerType: 'merged',
      score: 0.9,
      triggerTypes: ['motion', 'baby_cry'],
    ));
    final rows = await log.recent();
    expect(rows.single.triggerType, 'merged');
    expect(rows.single.triggerTypes, ['motion', 'baby_cry']);
    await log.close();
  });

  test('single-type event stores NULL trigger_types', () async {
    final log = await SqliteEventLog.open(inMemoryDatabasePath);
    await log.record(RecordedEvent(
      timestamp: DateTime(2026, 1, 1, 12),
      cameraName: 'Hallway',
      triggerType: 'motion',
      score: 0.8,
    ));
    final rows = await log.recent();
    expect(rows.single.triggerType, 'motion');
    expect(rows.single.triggerTypes, isEmpty);
    await log.close();
  });

  test('deleteEvents removes matching rows and returns their snapshot names',
      () async {
    final log = await SqliteEventLog.open(inMemoryDatabasePath);
    final now = DateTime.now();
    await log.record(RecordedEvent(
      timestamp: now.subtract(const Duration(days: 2)),
      cameraName: 'Hallway',
      triggerType: 'motion',
      score: 0.8,
      snapshotName: 'old.png',
    ));
    await log.record(RecordedEvent(
      timestamp: now.subtract(const Duration(minutes: 5)),
      cameraName: 'Hallway',
      triggerType: 'baby_cry',
      score: 0.7,
      snapshotName: 'recent.png',
    ));
    await log.record(RecordedEvent(
      timestamp: now,
      cameraName: 'Hallway',
      triggerType: 'loud_noise',
      score: 0.6,
      snapshotName: 'new.png',
    ));

    final removed =
        await log.deleteEvents(olderThan: now.subtract(const Duration(hours: 1)));
    expect(removed, ['old.png']);
    final remaining = await log.recent();
    expect(remaining.map((e) => e.triggerType), ['loud_noise', 'baby_cry']);
    await log.close();
  });

  test('deleteEvents with no cutoff removes everything', () async {
    final log = await SqliteEventLog.open(inMemoryDatabasePath);
    await log.record(RecordedEvent(
      timestamp: DateTime.now(),
      cameraName: 'Hallway',
      triggerType: 'motion',
      score: 0.8,
      snapshotName: 'a.png',
    ));
    final removed = await log.deleteEvents();
    expect(removed, ['a.png']);
    expect(await log.recent(), isEmpty);
    await log.close();
  });
}