import '../channels/log_channel.dart';
import '../channels/telegram_channel.dart';
import '../core/channel.dart';
import '../core/detector.dart';
import '../core/models.dart';
import '../detection/baby_cry_detector.dart';
import '../detection/glass_break_detector.dart';
import '../detection/loud_noise_detector.dart';
import '../detection/motion_detector.dart';

typedef DetectorFactory = Detector Function(DetectorConfig config);

final Map<String, DetectorFactory> detectorRegistry = {
  TriggerType.motion: (c) => MotionDetector(c),
  TriggerType.babyCry: (c) => BabyCryDetector(c),
  TriggerType.glassBreak: (c) => GlassBreakDetector(c),
  TriggerType.loudNoise: (c) => LoudNoiseDetector(c),
};

typedef ChannelFactory = Channel Function(ChannelConfig config);

final Map<String, ChannelFactory> channelRegistry = {
  'log': (c) => LogChannel(id: c.id, enabled: c.enabled),
  'telegram': (c) => TelegramChannel(
        id: c.id,
        enabled: c.enabled,
        settings: TelegramChannelSettings.fromJson(c.settingsJson),
      ),
};

/// Builds the typed [ChannelSettings] for a channel type (used by the settings
/// store to know which fields are secrets, and by the UI to edit them).
ChannelSettings buildChannelSettings(String type, Map<String, dynamic> json) {
  switch (type) {
    case 'log':
      return const _LogChannelSettings();
    case 'telegram':
      return TelegramChannelSettings.fromJson(json);
    default:
      throw ArgumentError.value(type, 'type', 'unsupported channel type');
  }
}

class _LogChannelSettings implements ChannelSettings {
  const _LogChannelSettings();

  @override
  String get type => 'log';

  @override
  Map<String, dynamic> toJson() => const {};

  @override
  List<String> get secretFields => const [];
}