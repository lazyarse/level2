import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:security_cam/channels/log_channel.dart';
import 'package:security_cam/channels/telegram_channel.dart';
import 'package:security_cam/core/camera_session.dart';
import 'package:security_cam/core/channel.dart';
import 'package:security_cam/core/detector.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/core/registries.dart';
import 'package:security_cam/event/event_pipeline.dart';
import 'package:security_cam/event/trigger_batcher.dart';
import 'package:security_cam/storage/event_recorder.dart';
import 'package:security_cam/storage/snapshot_store.dart';

class _FakeCamera implements CameraSession {
  @override
  String get cameraId => 'fake';

  @override
  Stream<AnalysisFrame> get analysisFrames => const Stream.empty();

  @override
  Future<void> init(CameraConfig config) async {}

  @override
  Future<Snapshot> takeSnapshot() async =>
      throw UnimplementedError('snapshot is supplied via TriggerBatch');

  @override
  Future<void> dispose() async {}
}

class _FakeRecorder implements EventRecorder {
  final List<RecordedEvent> recorded = [];

  @override
  Future<void> record(RecordedEvent event) async {
    recorded.add(event);
  }

  @override
  Future<DeletedMedia> deleteEvents({DateTime? olderThan}) async =>
      const DeletedMedia();
}

class _FakeSnapshotStore implements SnapshotStore {
  final List<Snapshot> saved = [];

  @override
  Future<String> save(Snapshot snapshot) async {
    saved.add(snapshot);
    return snapshot.name;
  }

  @override
  Future<Snapshot?> load(String name) async => null;

  @override
  Future<void> delete(String name) async {}
}

void main() {
  final base = DateTime(2026, 1, 1, 12, 0, 0);

  TriggerEvent trigger(String type, String detectorId, {double score = 0.7}) {
    return TriggerEvent(
      timestamp: base,
      triggerType: type,
      score: score,
      detectorId: detectorId,
    );
  }

  DetectorConfig config(String type, {List<String> routes = const ['telegram']}) {
    return DetectorConfig(
      type: type,
      threshold: 0.5,
      persistenceFrames: 1,
      routeToChannelIds: routes,
    );
  }

  ChannelConfig telegramConfig() =>
      const ChannelConfig(id: 'telegram', type: 'telegram');

  ChannelConfig logConfig() => const ChannelConfig(id: 'log', type: 'log');

  MockClient okClient({List<String>? log}) {
    return MockClient((request) async {
      log?.add(request.url.path);
      return http.Response('{"ok": true}', 200);
    });
  }

  Snapshot snap() => Snapshot(
        bytes: Uint8List.fromList([1]),
        mimeType: 'image/png',
        name: 'snap.png',
      );

  TriggerBatch batch(List<TriggerEvent> triggers,
      {Snapshot? snapshot, String? videoName}) {
    return TriggerBatch(
      timestamp: base,
      triggers: triggers,
      snapshot: snapshot,
      videoName: videoName,
    );
  }

  EventPipeline pipeline({
    required _FakeRecorder recorder,
    required _FakeSnapshotStore snapshots,
    required Map<String, ChannelConfig> channels,
    Map<String, DetectorConfig> detectors = const {},
    Map<String, ChannelFactory>? factories,
    Future<void> Function(Duration)? sleep,
  }) {
    return EventPipeline(
      cameraSession: _FakeCamera(),
      cameraName: 'Hallway',
      detectorConfigs: detectors,
      channelConfigs: channels,
      recorder: recorder,
      snapshotStore: snapshots,
      channelFactories: factories,
      sleep: sleep,
    );
  }

  test('merges triggers, routes once, records merged entry and saves snapshot',
      () async {
    final recorder = _FakeRecorder();
    final snapshots = _FakeSnapshotStore();
    final requests = <String>[];
    final tg = TelegramChannel(
      id: 'telegram',
      enabled: true,
      settings: const TelegramChannelSettings(botToken: '123456:ABC', chatId: '1'),
      client: okClient(log: requests),
    );
    final p = pipeline(
      recorder: recorder,
      snapshots: snapshots,
      channels: {'telegram': telegramConfig()},
      detectors: {
        'motion': config('motion'),
        'baby_cry': config('baby_cry'),
      },
      factories: {'telegram': (c) => tg},
    );

    await p.handleBatch(batch(
      [trigger('motion', 'motion', score: 0.5), trigger('baby_cry', 'baby_cry', score: 0.9)],
      snapshot: snap(),
    ));

    expect(recorder.recorded, hasLength(1));
    expect(recorder.recorded.single.triggerType, 'merged');
    expect(recorder.recorded.single.triggerTypes, ['motion', 'baby_cry']);
    expect(recorder.recorded.single.score, 0.9);
    expect(recorder.recorded.single.snapshotName, 'snap.png');
    expect(snapshots.saved, hasLength(1));
    expect(requests, hasLength(1));
  });

  test('records the batch videoName on the event', () async {
    final recorder = _FakeRecorder();
    final p = pipeline(
      recorder: recorder,
      snapshots: _FakeSnapshotStore(),
      channels: {'log': logConfig()},
      factories: {
        'log': (c) => LogChannel(id: 'log'),
      },
    );

    await p.handleBatch(batch([trigger('motion', 'motion')],
        videoName: '2026-01-01_12-00-00-000_Hallway.mp4'));

    expect(recorder.recorded.single.videoName,
        '2026-01-01_12-00-00-000_Hallway.mp4');
  });

  test('single-trigger batch records its own type with empty triggerTypes',
      () async {
    final recorder = _FakeRecorder();
    final p = pipeline(
      recorder: recorder,
      snapshots: _FakeSnapshotStore(),
      channels: {'telegram': telegramConfig()},
      detectors: {'motion': config('motion')},
      factories: {'telegram': (c) => TelegramChannel(
        id: 'telegram',
        enabled: true,
        settings: const TelegramChannelSettings(botToken: '123456:ABC', chatId: '1'),
        client: okClient(),
      )},
    );

    await p.handleBatch(batch([trigger('motion', 'motion')]));

    expect(recorder.recorded.single.triggerType, 'motion');
    expect(recorder.recorded.single.triggerTypes, isEmpty);
  });

  test('empty routeToChannelIds targets all enabled channels', () async {
    final recorder = _FakeRecorder();
    final log = LogChannel(id: 'log');
    final requests = <String>[];
    final p = pipeline(
      recorder: recorder,
      snapshots: _FakeSnapshotStore(),
      channels: {'telegram': telegramConfig(), 'log': logConfig()},
      detectors: {'motion': config('motion', routes: const [])},
      factories: {
        'telegram': (c) => TelegramChannel(
          id: 'telegram',
          enabled: true,
          settings: const TelegramChannelSettings(botToken: '123456:ABC', chatId: '1'),
          client: okClient(log: requests),
        ),
        'log': (c) => log,
      },
    );

    await p.handleBatch(batch([trigger('motion', 'motion')]));

    expect(log.sent, hasLength(1));
    expect(requests, hasLength(1));
    expect(recorder.recorded.single.channelStatuses, {
      'log': 'delivered',
      'telegram': 'delivered',
    });
  });

  test('log channel is always enabled for routing even when disabled', () async {
    final recorder = _FakeRecorder();
    final log = LogChannel(id: 'log');
    final p = pipeline(
      recorder: recorder,
      snapshots: _FakeSnapshotStore(),
      channels: {
        'log': const ChannelConfig(id: 'log', type: 'log', enabled: false),
      },
      detectors: {'motion': config('motion', routes: const [])},
      factories: {'log': (c) => log},
    );

    await p.handleBatch(batch([trigger('motion', 'motion')]));

    expect(log.sent, hasLength(1));
    expect(recorder.recorded.single.channelStatuses, {'log': 'delivered'});
  });

  test('missing detector config contributes nothing (log-only fallback)',
      () async {
    final recorder = _FakeRecorder();
    final p = pipeline(
      recorder: recorder,
      snapshots: _FakeSnapshotStore(),
      channels: {'telegram': telegramConfig(), 'log': logConfig()},
      detectors: const {},
      factories: {'telegram': (c) => TelegramChannel(
        id: 'telegram',
        enabled: true,
        settings: const TelegramChannelSettings(botToken: '123456:ABC', chatId: '1'),
        client: okClient(),
      )},
    );

    await p.handleBatch(batch([trigger('motion', 'ghost')]));

    expect(recorder.recorded.single.channelStatuses, isEmpty);
    expect(recorder.recorded.single.triggerType, 'motion');
  });

  test('channel failure records a failed status but still records the event',
      () async {
    final recorder = _FakeRecorder();
    final failing = MockClient((request) async {
      return http.Response('{"ok": false}', 500);
    });
    final p = pipeline(
      recorder: recorder,
      snapshots: _FakeSnapshotStore(),
      channels: {'telegram': telegramConfig()},
      detectors: {'motion': config('motion')},
      factories: {'telegram': (c) => TelegramChannel(
        id: 'telegram',
        enabled: true,
        settings: const TelegramChannelSettings(botToken: '123456:ABC', chatId: '1'),
        client: failing,
      )},
      sleep: (_) async {},
    );

    await p.handleBatch(batch([trigger('motion', 'motion')]));

    expect(recorder.recorded.single.channelStatuses, {'telegram': 'failed'});
  });

  test('retries a flaky channel and delivers on a later attempt', () async {
    final recorder = _FakeRecorder();
    var attempts = 0;
    final flaky = MockClient((request) async {
      attempts++;
      if (attempts < 3) {
        return http.Response('{"ok": false}', 500);
      }
      return http.Response('{"ok": true}', 200);
    });
    final sleeps = <Duration>[];
    final p = pipeline(
      recorder: recorder,
      snapshots: _FakeSnapshotStore(),
      channels: {'telegram': telegramConfig()},
      detectors: {'motion': config('motion')},
      factories: {'telegram': (c) => TelegramChannel(
        id: 'telegram',
        enabled: true,
        settings: const TelegramChannelSettings(botToken: '123456:ABC', chatId: '1'),
        client: flaky,
      )},
      sleep: (d) async => sleeps.add(d),
    );

    await p.handleBatch(batch([trigger('motion', 'motion')]));

    expect(attempts, 3);
    expect(recorder.recorded.single.channelStatuses, {'telegram': 'delivered'});
    expect(sleeps, [const Duration(seconds: 1), const Duration(seconds: 2)]);
  });

  test('triggerLabel maps face', () {
    expect(triggerLabel(TriggerType.face), 'Face');
  });

  test('exhausted retries back off 1s then 2s and record failed', () async {
    final recorder = _FakeRecorder();
    var attempts = 0;
    final alwaysFails = MockClient((request) async {
      attempts++;
      return http.Response('{"ok": false}', 500);
    });
    final sleeps = <Duration>[];
    final p = pipeline(
      recorder: recorder,
      snapshots: _FakeSnapshotStore(),
      channels: {'telegram': telegramConfig()},
      detectors: {'motion': config('motion')},
      factories: {'telegram': (c) => TelegramChannel(
        id: 'telegram',
        enabled: true,
        settings: const TelegramChannelSettings(botToken: '123456:ABC', chatId: '1'),
        client: alwaysFails,
      )},
      sleep: (d) async => sleeps.add(d),
    );

    await p.handleBatch(batch([trigger('motion', 'motion')]));

    expect(attempts, 3);
    expect(recorder.recorded.single.channelStatuses, {'telegram': 'failed'});
    expect(sleeps, [const Duration(seconds: 1), const Duration(seconds: 2)]);
  });
}