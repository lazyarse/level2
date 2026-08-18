import '../core/channel.dart';
import '../core/detector.dart';
import '../core/models.dart';

/// Camera source choices (desktop dev-only; the mobile `camera_service` module
/// and iOS plugin ignore these and always use the on-device camera).
class CameraSource {
  static const simulated = 'simulated';
  static const webcam = 'webcam';
  static const file = 'file';

  const CameraSource._();
}

/// Audio source choices (desktop dev-only; the mobile module/plugin always use
/// the on-device microphone).
class AudioInput {
  static const simulated = 'simulated';
  static const mic = 'mic';
  static const file = 'file';

  const AudioInput._();
}

class AppSettings {
  final String cameraName;
  final String cameraSource;
  final String? cameraSourcePath;
  final String audioSource;
  final String? audioSourcePath;
  final Map<String, DetectorConfig> detectorConfigs;
  final List<ChannelConfig> channelConfigs;
  final Duration notificationMergeWindow;
  final int retentionDays;

  /// Seconds of footage kept before a trigger and captured after it (Android
  /// only; the ring buffer and post-roll tail are sized from these).
  final int preRollSeconds;
  final int postRollSeconds;

  const AppSettings({
    this.cameraName = 'Hallway',
    this.cameraSource = CameraSource.simulated,
    this.cameraSourcePath,
    this.audioSource = AudioInput.simulated,
    this.audioSourcePath,
    this.detectorConfigs = const {},
    this.channelConfigs = const [],
    this.notificationMergeWindow = const Duration(seconds: 3),
    this.retentionDays = 7,
    this.preRollSeconds = 5,
    this.postRollSeconds = 5,
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
        ChannelConfig(id: 'telegram', type: 'telegram', enabled: false),
        ChannelConfig(id: 'email', type: 'email', enabled: false),
        ChannelConfig(id: 'discord', type: 'discord', enabled: false),
      ],
    );
  }

  AppSettings copyWith({
    String? cameraName,
    String? cameraSource,
    String? cameraSourcePath,
    bool clearCameraSourcePath = false,
    String? audioSource,
    String? audioSourcePath,
    bool clearAudioSourcePath = false,
    Map<String, DetectorConfig>? detectorConfigs,
    List<ChannelConfig>? channelConfigs,
    Duration? notificationMergeWindow,
    int? retentionDays,
    int? preRollSeconds,
    int? postRollSeconds,
  }) {
    return AppSettings(
      cameraName: cameraName ?? this.cameraName,
      cameraSource: cameraSource ?? this.cameraSource,
      cameraSourcePath: clearCameraSourcePath
          ? null
          : cameraSourcePath ?? this.cameraSourcePath,
      audioSource: audioSource ?? this.audioSource,
      audioSourcePath: clearAudioSourcePath
          ? null
          : audioSourcePath ?? this.audioSourcePath,
      detectorConfigs: detectorConfigs ?? this.detectorConfigs,
      channelConfigs: channelConfigs ?? this.channelConfigs,
      notificationMergeWindow: notificationMergeWindow ?? this.notificationMergeWindow,
      retentionDays: retentionDays ?? this.retentionDays,
      preRollSeconds: preRollSeconds ?? this.preRollSeconds,
      postRollSeconds: postRollSeconds ?? this.postRollSeconds,
    );
  }

  Map<String, dynamic> toJson() => {
        'cameraName': cameraName,
        'cameraSource': cameraSource,
        if (cameraSourcePath != null) 'cameraSourcePath': cameraSourcePath,
        'audioSource': audioSource,
        if (audioSourcePath != null) 'audioSourcePath': audioSourcePath,
        'detectorConfigs': detectorConfigs.map((k, v) => MapEntry(k, v.toJson())),
        'channelConfigs': channelConfigs.map((c) => c.toJson()).toList(),
        'notificationMergeWindowMs': notificationMergeWindow.inMilliseconds,
        'retentionDays': retentionDays,
        'preRollSeconds': preRollSeconds,
        'postRollSeconds': postRollSeconds,
      };

  factory AppSettings.fromJson(Map<String, dynamic> json) {
    final defaults = AppSettings.defaults();
    final detectors = (json['detectorConfigs'] as Map?)
        ?.map((k, v) => MapEntry(
            k as String, DetectorConfig.fromJson(v as Map<String, dynamic>)));
    final stored = (json['channelConfigs'] as List?)
        ?.map((e) => ChannelConfig.fromJson(e as Map<String, dynamic>))
        .toList() ??
    const <ChannelConfig>[];
    final channels = [
      ...stored,
      for (final d in defaults.channelConfigs)
        if (!stored.any((c) => c.id == d.id)) d,
    ];
    return AppSettings(
      cameraName: json['cameraName'] as String? ?? defaults.cameraName,
      cameraSource:
          json['cameraSource'] as String? ?? defaults.cameraSource,
      cameraSourcePath: json['cameraSourcePath'] as String?,
      audioSource: json['audioSource'] as String? ?? defaults.audioSource,
      audioSourcePath: json['audioSourcePath'] as String?,
      detectorConfigs: detectors ?? defaults.detectorConfigs,
      channelConfigs: channels,
      notificationMergeWindow: Duration(
        milliseconds: json['notificationMergeWindowMs'] as int? ??
            defaults.notificationMergeWindow.inMilliseconds,
      ),
      retentionDays: json['retentionDays'] as int? ?? defaults.retentionDays,
      preRollSeconds:
          json['preRollSeconds'] as int? ?? defaults.preRollSeconds,
      postRollSeconds:
          json['postRollSeconds'] as int? ?? defaults.postRollSeconds,
    );
  }
}