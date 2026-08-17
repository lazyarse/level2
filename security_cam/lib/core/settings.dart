import '../core/channel.dart';
import '../core/detector.dart';
import '../core/models.dart';

class AppSettings {
  final String cameraName;
  final Map<String, DetectorConfig> detectorConfigs;
  final List<ChannelConfig> channelConfigs;

  const AppSettings({
    this.cameraName = 'Hallway',
    this.detectorConfigs = const {},
    this.channelConfigs = const [],
  });

  static AppSettings defaults() {
    return AppSettings(
      cameraName: 'Hallway',
      detectorConfigs: {
        TriggerType.motion: const DetectorConfig(
          type: TriggerType.motion,
          threshold: 0.08,
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
  }) {
    return AppSettings(
      cameraName: cameraName ?? this.cameraName,
      detectorConfigs: detectorConfigs ?? this.detectorConfigs,
      channelConfigs: channelConfigs ?? this.channelConfigs,
    );
  }

  Map<String, dynamic> toJson() => {
        'cameraName': cameraName,
        'detectorConfigs': detectorConfigs.map((k, v) => MapEntry(k, v.toJson())),
        'channelConfigs': channelConfigs.map((c) => c.toJson()).toList(),
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
    );
  }
}