import 'dart:convert';

import 'package:sqflite/sqflite.dart';

import 'event_recorder.dart';

class RecordedEventRow {
  final int id;
  final DateTime timestamp;
  final String cameraName;
  final String triggerType;
  final double score;
  final String? snapshotName;
  final Map<String, String> channelStatuses;

  RecordedEventRow({
    required this.id,
    required this.timestamp,
    required this.cameraName,
    required this.triggerType,
    required this.score,
    this.snapshotName,
    this.channelStatuses = const {},
  });
}

class SqliteEventLog implements EventRecorder {
  final Database _db;
  static const _table = 'events';

  SqliteEventLog._(this._db);

  static Future<SqliteEventLog> open(String path) async {
    final db = await openDatabase(
      path,
      version: 1,
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE $_table (
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
    return SqliteEventLog._(db);
  }

  @override
  Future<void> record(RecordedEvent event) async {
    await _db.insert(_table, {
      'timestamp': event.timestamp.toIso8601String(),
      'camera_name': event.cameraName,
      'trigger_type': event.triggerType,
      'score': event.score,
      'snapshot_name': event.snapshotName,
      'channel_statuses': jsonEncode(event.channelStatuses),
    });
  }

  Future<List<RecordedEventRow>> recent({int limit = 100}) async {
    final rows = await _db.query(_table, orderBy: 'timestamp DESC', limit: limit);
    return rows.map(_rowFromMap).toList();
  }

  RecordedEventRow _rowFromMap(Map<String, Object?> map) {
    Map<String, String> statuses = {};
    final raw = map['channel_statuses'] as String?;
    if (raw != null) {
      try {
        statuses = (jsonDecode(raw) as Map).cast<String, String>();
      } catch (_) {}
    }
    return RecordedEventRow(
      id: map['id'] as int,
      timestamp: DateTime.parse(map['timestamp'] as String),
      cameraName: map['camera_name'] as String,
      triggerType: map['trigger_type'] as String,
      score: (map['score'] as num).toDouble(),
      snapshotName: map['snapshot_name'] as String?,
      channelStatuses: statuses,
    );
  }

  Future<void> close() async {
    await _db.close();
  }
}