import '../channels/log_channel.dart';
import '../channels/telegram_channel.dart';
import '../core/channel.dart';
import '../core/detector.dart';
import '../core/models.dart';
import '../detection/baby_cry_detector.dart';
import '../detection/glass_break_detector.dart';
import '../detection/motion_detector.dart';

typedef DetectorFactory = Detector Function(DetectorConfig config);

final Map<String, DetectorFactory> detectorRegistry = {
  TriggerType.motion: (c) => MotionDetector(c),
  TriggerType.babyCry: (c) => BabyCryDetector(c),
  TriggerType.glassBreak: (c) => GlassBreakDetector(c),
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