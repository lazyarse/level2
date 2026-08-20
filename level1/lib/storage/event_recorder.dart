class RecordedEvent {
  final DateTime timestamp;
  final String cameraName;
  final String triggerType;
  final double score;
  final String? snapshotName;
  final String? videoName;
  final Map<String, String> channelStatuses;
  final List<String> triggerTypes;

  RecordedEvent({
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

/// Names of media files referenced by deleted event rows, so the controller
/// can clean up both snapshot and video stores.
class DeletedMedia {
  final List<String> snapshotNames;
  final List<String> videoNames;

  const DeletedMedia({this.snapshotNames = const [], this.videoNames = const []});
}

abstract class EventRecorder {
  Future<void> record(RecordedEvent event);

  Future<DeletedMedia> deleteEvents({DateTime? olderThan});
}