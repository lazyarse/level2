class RecordedEvent {
  final DateTime timestamp;
  final String cameraName;
  final String triggerType;
  final double score;
  final String? snapshotName;
  final Map<String, String> channelStatuses;

  RecordedEvent({
    required this.timestamp,
    required this.cameraName,
    required this.triggerType,
    required this.score,
    this.snapshotName,
    this.channelStatuses = const {},
  });
}

abstract class EventRecorder {
  Future<void> record(RecordedEvent event);
}