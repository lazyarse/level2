import '../core/models.dart';
import '../detection/audio/audio_classifier.dart';

class DetectorConfig {
  final String type;
  final bool enabled;
  final double threshold;
  final int persistenceFrames;
  final Duration cooldown;
  final List<String> routeToChannelIds;
  final bool motionGated;

  const DetectorConfig({
    required this.type,
    this.enabled = true,
    this.threshold = 0.5,
    this.persistenceFrames = 2,
    this.cooldown = const Duration(seconds: 60),
    this.routeToChannelIds = const [],
    this.motionGated = false,
  });

  DetectorConfig copyWith({
    String? type,
    bool? enabled,
    double? threshold,
    int? persistenceFrames,
    Duration? cooldown,
    List<String>? routeToChannelIds,
    bool? motionGated,
  }) {
    return DetectorConfig(
      type: type ?? this.type,
      enabled: enabled ?? this.enabled,
      threshold: threshold ?? this.threshold,
      persistenceFrames: persistenceFrames ?? this.persistenceFrames,
      cooldown: cooldown ?? this.cooldown,
      routeToChannelIds: routeToChannelIds ?? this.routeToChannelIds,
      motionGated: motionGated ?? this.motionGated,
    );
  }

  Map<String, dynamic> toJson() => {
        'type': type,
        'enabled': enabled,
        'threshold': threshold,
        'persistenceFrames': persistenceFrames,
        'cooldownMs': cooldown.inMilliseconds,
        'routeToChannelIds': routeToChannelIds,
        'motionGated': motionGated,
      };

  factory DetectorConfig.fromJson(Map<String, dynamic> json) => DetectorConfig(
        type: json['type'] as String,
        enabled: json['enabled'] as bool? ?? true,
        threshold: (json['threshold'] as num?)?.toDouble() ?? 0.5,
        persistenceFrames: json['persistenceFrames'] as int? ?? 2,
        cooldown: Duration(milliseconds: json['cooldownMs'] as int? ?? 60000),
        routeToChannelIds:
            (json['routeToChannelIds'] as List?)?.cast<String>() ?? const [],
        motionGated: json['motionGated'] as bool? ?? false,
      );
}

abstract class Detector {
  String get id;

  DetectorConfig get config;

  String get triggerType;

  Future<void> init();

  void reset();

  Future<void> dispose();
}

abstract class FrameDetector extends Detector {
  /// Inclusion regions (normalized 0..1 on the analysis frame). Empty = detect
  /// everywhere. Set by the pipeline via [DetectorPipeline.setRegions].
  List<DetectionRegion> regions = const [];

  DetectionResult analyzeFrame(AnalysisFrame frame);

  /// Async analysis path for gated/heavy detectors (runs off the pipeline's
  /// sync per-frame loop). Defaults to the sync path wrapped in a Future.
  Future<DetectionResult> analyzeFrameAsync(AnalysisFrame frame) async =>
      analyzeFrame(frame);
}

abstract class AudioDetector extends Detector {
  DetectionResult analyzeScores(AudioEventScores scores);
}
