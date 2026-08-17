import '../core/channel.dart';
import '../core/detector.dart';
import '../core/models.dart';

class AppSettings {
  final String cameraName;
  final Map<String, DetectorConfig> detectorConfigs;
  final List<ChannelConfig> channelConfigs;
  final Duration notificationMergeWindow;

  const AppSettings({
    this.cameraName = 'Hallway',
    this.detectorConfigs = const {},
    this.channelConfigs = const [],
    this.notificationMergeWindow = const Duration(seconds: 3),
  });

  static AppSettings defaults() {
    return AppSettings(
      cameraName: 'Hallway',
      detectorConfigs: {
        TriggerType.motion: const DetectorConfig(
          type: TriggerType.motion,
          threshold: 0.03,
          persistenceFrames: 2,
          routeToChannelIds: ['telegram'],
        ),
        TriggerType.babyCry: const DetectorConfig(
          type: TriggerType.babyCry,
          threshold: 0.5,
          persistenceFrames: 2,
          routeToChannelIds: ['telegram'],
        ),
        TriggerType.glassBreak: const DetectorConfig(
          type: TriggerType.glassBreak,
          threshold: 0.5,
          persistenceFrames: 2,
          enabled: false,
          routeToChannelIds: ['telegram'],
        ),
        TriggerType.loudNoise: const DetectorConfig(
          type: TriggerType.loudNoise,
          threshold: 0.5,
          persistenceFrames: 1,
          enabled: false,
          routeToChannelIds: ['telegram'],
        ),
      },
      channelConfigs: const [
        ChannelConfig(id: 'log', type: 'log', enabled: true),
      ],
    );
  }

  AppSettings copyWith({
    String? cameraName,
    Map<String, DetectorConfig>? detectorConfigs,
    List<ChannelConfig>? channelConfigs,
    Duration? notificationMergeWindow,
  }) {
    return AppSettings(
      cameraName: cameraName ?? this.cameraName,
      detectorConfigs: detectorConfigs ?? this.detectorConfigs,
      channelConfigs: channelConfigs ?? this.channelConfigs,
      notificationMergeWindow: notificationMergeWindow ?? this.notificationMergeWindow,
    );
  }

  Map<String, dynamic> toJson() => {
        'cameraName': cameraName,
        'detectorConfigs': detectorConfigs.map((k, v) => MapEntry(k, v.toJson())),
        'channelConfigs': channelConfigs.map((c) => c.toJson()).toList(),
        'notificationMergeWindowMs': notificationMergeWindow.inMilliseconds,
      };

  factory AppSettings.fromJson(Map<String, dynamic> json) {
    final defaults = AppSettings.defaults();
    final detectors = (json['detectorConfigs'] as Map?)
        ?.map((k, v) => MapEntry(
            k as String, DetectorConfig.fromJson(v as Map<String, dynamic>)));
    final channels = (json['channelConfigs'] as List?)
        ?.map((e) => ChannelConfig.fromJson(e as Map<String, dynamic>))
        .toList();
    return AppSettings(
      cameraName: json['cameraName'] as String? ?? defaults.cameraName,
      detectorConfigs: detectors ?? defaults.detectorConfigs,
      channelConfigs: channels ?? defaults.channelConfigs,
      notificationMergeWindow: Duration(
        milliseconds: json['notificationMergeWindowMs'] as int? ??
            defaults.notificationMergeWindow.inMilliseconds,
      ),
    );
  }
}