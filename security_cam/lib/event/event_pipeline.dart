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
  final CameraSession cameraSession;
  final String cameraName;
  final Map<String, DetectorConfig> detectorConfigs;
  final Map<String, ChannelConfig> channelConfigs;
  final EventRecorder recorder;
  final SnapshotStore snapshotStore;

  EventPipeline({
    required this.cameraSession,
    required this.cameraName,
    required this.detectorConfigs,
    required this.channelConfigs,
    required this.recorder,
    required this.snapshotStore,
  });

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
      final channel = channelRegistry[target.type]!(target);
      try {
        await channel.send(message);
        statuses[target.id] = 'delivered';
      } catch (_) {
        statuses[target.id] = 'failed';
      }
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

  List<ChannelConfig> _targetsFor(List<TriggerEvent> triggers) {
    final anyEmptyRoutes = triggers.any((t) {
      final config = detectorConfigs[t.detectorId];
      return config != null && config.routeToChannelIds.isEmpty;
    });
    return channelConfigs.values
        .where((c) => c.enabled)
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