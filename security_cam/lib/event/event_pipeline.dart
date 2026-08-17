import 'dart:math';

import '../core/camera_session.dart';
import '../core/channel.dart';
import '../core/detector.dart';
import '../core/models.dart';
import '../core/registries.dart';
import '../storage/event_recorder.dart';
import '../storage/snapshot_store.dart';
import 'trigger_batcher.dart';

class EventPipeline {
  static const defaultBackoffDelays = [
    Duration(seconds: 1),
    Duration(seconds: 2),
    Duration(seconds: 4),
  ];

  final CameraSession cameraSession;
  final String cameraName;
  final Map<String, DetectorConfig> detectorConfigs;
  final Map<String, ChannelConfig> channelConfigs;
  final EventRecorder recorder;
  final SnapshotStore snapshotStore;
  final Map<String, ChannelFactory> _channelFactories;
  final int maxAttempts;
  final List<Duration> backoffDelays;
  final Future<void> Function(Duration) _sleep;

  EventPipeline({
    required this.cameraSession,
    required this.cameraName,
    required this.detectorConfigs,
    required this.channelConfigs,
    required this.recorder,
    required this.snapshotStore,
    Map<String, ChannelFactory>? channelFactories,
    this.maxAttempts = 3,
    this.backoffDelays = defaultBackoffDelays,
    Future<void> Function(Duration)? sleep,
  })  : _channelFactories = channelFactories ?? channelRegistry,
        _sleep = sleep ?? Future.delayed;

  Future<void> handleBatch(TriggerBatch batch) async {
    final types = <String>{
      for (final t in batch.triggers) t.triggerType,
    }.toList();
    final single = types.length == 1;
    final type = single ? types.first : TriggerType.merged;

    final snapshot = batch.snapshot;
    if (snapshot != null) {
      try {
        await snapshotStore.save(snapshot);
      } catch (_) {}
    }

    final text = _alertText(types, batch.timestamp, snapshot);
    final message = AlertMessage(
      timestamp: batch.timestamp,
      triggerType: type,
      text: text,
      snapshot: snapshot,
    );

    final targets = _targetsFor(batch.triggers);

    final statuses = <String, String>{};
    for (final target in targets) {
      final channel = _channelFactories[target.type]!(target);
      statuses[target.id] = await _sendWithRetry(channel, message);
    }

    await recorder.record(RecordedEvent(
      timestamp: batch.timestamp,
      cameraName: cameraName,
      triggerType: type,
      triggerTypes: single ? const [] : types,
      score: batch.triggers.map((e) => e.score).reduce(max),
      snapshotName: snapshot?.name,
      channelStatuses: statuses,
    ));
  }

  /// Sends with up to [maxAttempts] attempts, backing off between failures.
  /// Returns `delivered` on success or `failed` after exhausting attempts.
  Future<String> _sendWithRetry(Channel channel, AlertMessage message) async {
    for (var attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        await channel.send(message);
        return 'delivered';
      } catch (_) {
        if (attempt == maxAttempts - 1) return 'failed';
        await _sleep(backoffDelays[attempt]);
      }
    }
    return 'failed';
  }

  List<ChannelConfig> _targetsFor(List<TriggerEvent> triggers) {
    final anyEmptyRoutes = triggers.any((t) {
      final config = detectorConfigs[t.detectorId];
      return config != null && config.routeToChannelIds.isEmpty;
    });
    return channelConfigs.values
        .where((c) => c.enabled || c.type == 'log')
        .where((c) =>
            anyEmptyRoutes ||
            triggers.any((t) {
              final config = detectorConfigs[t.detectorId];
              return config != null && config.routeToChannelIds.contains(c.id);
            }))
        .toList();
  }

  String _alertText(List<String> types, DateTime timestamp, Snapshot? snapshot) {
    final label = types.length == 1
        ? triggerLabel(types.first)
        : types.map(triggerLabel).join(' + ');
    final time = timestamp.toLocal();
    return '$label detected in $cameraName at ${time.toIso8601String()}';
  }
}

String triggerLabel(String triggerType) {
  switch (triggerType) {
    case TriggerType.motion:
      return 'Motion';
    case TriggerType.babyCry:
      return 'Baby crying';
    case TriggerType.glassBreak:
      return 'Glass breaking';
    case TriggerType.loudNoise:
      return 'Loud noise';
    case TriggerType.merged:
      return 'Multiple triggers';
    case TriggerType.person:
      return 'Person';
    default:
      return 'Activity';
  }
}