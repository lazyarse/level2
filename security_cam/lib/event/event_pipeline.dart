import '../core/camera_session.dart';
import '../core/channel.dart';
import '../core/detector.dart';
import '../core/models.dart';
import '../core/registries.dart';
import '../storage/event_recorder.dart';
import '../storage/snapshot_store.dart';

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

  Future<void> handleTrigger(TriggerEvent event) async {
    final config = detectorConfigs[event.detectorId];
    if (config == null) return;

    Snapshot? snapshot;
    try {
      snapshot = await cameraSession.takeSnapshot();
      await snapshotStore.save(snapshot);
    } catch (_) {
      snapshot = null;
    }

    final text = _alertText(event, snapshot);
    final message = AlertMessage(
      timestamp: event.timestamp,
      triggerType: event.triggerType,
      text: text,
      snapshot: snapshot,
    );

    final targets = channelConfigs.values
        .where((c) => c.enabled)
        .where((c) => config.routeToChannelIds.isEmpty || config.routeToChannelIds.contains(c.id))
        .toList();

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
      timestamp: event.timestamp,
      cameraName: cameraName,
      triggerType: event.triggerType,
      score: event.score,
      snapshotName: snapshot?.name,
      channelStatuses: statuses,
    ));
  }

  String _alertText(TriggerEvent event, Snapshot? snapshot) {
    final label = triggerLabel(event.triggerType);
    final time = event.timestamp.toLocal();
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
    case TriggerType.person:
      return 'Person';
    default:
      return 'Activity';
  }
}