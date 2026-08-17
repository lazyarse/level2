import '../channels/discord_channel.dart';
import '../channels/email_channel.dart';
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

Channel _logChannel(ChannelConfig c) => LogChannel(id: c.id, enabled: c.enabled);

Channel _telegramChannel(ChannelConfig c) => TelegramChannel(
      id: c.id,
      enabled: c.enabled,
      settings: TelegramChannelSettings.fromJson(c.settingsJson),
    );

Channel _emailChannel(ChannelConfig c) => EmailChannel(
      id: c.id,
      enabled: c.enabled,
      settings: EmailChannelSettings.fromJson(c.settingsJson),
    );

Channel _discordChannel(ChannelConfig c) => DiscordChannel(
      id: c.id,
      enabled: c.enabled,
      settings: DiscordChannelSettings.fromJson(c.settingsJson),
    );

/// Top-level function references (sendable to a worker isolate).
final Map<String, ChannelFactory> channelRegistry = {
  'log': _logChannel,
  'telegram': _telegramChannel,
  'email': _emailChannel,
  'discord': _discordChannel,
};

/// Builds the typed [ChannelSettings] for a channel type (used by the settings
/// store to know which fields are secrets, and by the UI to edit them).
ChannelSettings buildChannelSettings(String type, Map<String, dynamic> json) {
  switch (type) {
    case 'log':
      return const _LogChannelSettings();
    case 'telegram':
      return TelegramChannelSettings.fromJson(json);
    case 'email':
      return EmailChannelSettings.fromJson(json);
    case 'discord':
      return DiscordChannelSettings.fromJson(json);
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