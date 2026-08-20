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
  final String? videoName;
  final Map<String, String> channelStatuses;
  final List<String> triggerTypes;

  RecordedEventRow({
    required this.id,
    required this.timestamp,
    required this.cameraName,
    required this.triggerType,
    required this.score,
    this.snapshotName,
    this.videoName,
    this.channelStatuses = const {},
    this.triggerTypes = const [],
  });
}

class SqliteEventLog implements EventRecorder {
  final Database _db;
  static const _table = 'events';
  static const _version = 3;

  SqliteEventLog._(this._db);

  static Future<SqliteEventLog> open(String path) async {
    final db = await openDatabase(
      path,
      version: _version,
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE $_table (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TEXT NOT NULL,
            camera_name TEXT NOT NULL,
            trigger_type TEXT NOT NULL,
            score REAL NOT NULL,
            snapshot_name TEXT,
            video_name TEXT,
            channel_statuses TEXT,
            trigger_types TEXT
          )
        ''');
      },
      onUpgrade: (db, oldVersion, newVersion) async {
        if (oldVersion < 2) {
          await db.execute('ALTER TABLE $_table ADD COLUMN trigger_types TEXT');
        }
        if (oldVersion < 3) {
          await db.execute('ALTER TABLE $_table ADD COLUMN video_name TEXT');
        }
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
      'video_name': event.videoName,
      'channel_statuses': jsonEncode(event.channelStatuses),
      'trigger_types': event.triggerTypes.isEmpty
          ? null
          : jsonEncode(event.triggerTypes),
    });
  }

  @override
  Future<DeletedMedia> deleteEvents({DateTime? olderThan}) async {
    final where = olderThan == null ? null : 'timestamp < ?';
    final whereArgs =
        olderThan == null ? null : [olderThan.toIso8601String()];
    final rows = await _db.query(
      _table,
      columns: ['snapshot_name', 'video_name'],
      where: where,
      whereArgs: whereArgs,
    );
    final deleted = DeletedMedia(
      snapshotNames: rows
          .map((r) => r['snapshot_name'] as String?)
          .whereType<String>()
          .toList(),
      videoNames: rows
          .map((r) => r['video_name'] as String?)
          .whereType<String>()
          .toList(),
    );
    await _db.delete(_table, where: where, whereArgs: whereArgs);
    return deleted;
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
    final triggerTypes = <String>[];
    final rawTypes = map['trigger_types'] as String?;
    if (rawTypes != null) {
      try {
        triggerTypes.addAll((jsonDecode(rawTypes) as List).cast<String>());
      } catch (_) {}
    }
    return RecordedEventRow(
      id: map['id'] as int,
      timestamp: DateTime.parse(map['timestamp'] as String),
      cameraName: map['camera_name'] as String,
      triggerType: map['trigger_type'] as String,
      score: (map['score'] as num).toDouble(),
      snapshotName: map['snapshot_name'] as String?,
      videoName: map['video_name'] as String?,
      channelStatuses: statuses,
      triggerTypes: triggerTypes,
    );
  }

  Future<void> close() async {
    await _db.close();
  }
}