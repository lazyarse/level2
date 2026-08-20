import 'models.dart';

abstract class ChannelSettings {
  String get type;

  Map<String, dynamic> toJson();

  List<String> get secretFields;
}

class ChannelConfig {
  final String id;
  final String type;
  final bool enabled;
  final Map<String, dynamic> settingsJson;

  const ChannelConfig({
    required this.id,
    required this.type,
    this.enabled = true,
    this.settingsJson = const {},
  });

  ChannelConfig copyWith({
    bool? enabled,
    Map<String, dynamic>? settingsJson,
  }) {
    return ChannelConfig(
      id: id,
      type: type,
      enabled: enabled ?? this.enabled,
      settingsJson: settingsJson ?? this.settingsJson,
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'type': type,
        'enabled': enabled,
        'settings': settingsJson,
      };

  factory ChannelConfig.fromJson(Map<String, dynamic> json) => ChannelConfig(
        id: json['id'] as String,
        type: json['type'] as String,
        enabled: json['enabled'] as bool? ?? true,
        settingsJson: (json['settings'] as Map?)?.cast<String, dynamic>() ?? const {},
      );
}

abstract class Channel {
  String get id;

  String get type;

  bool get enabled;

  ChannelSettings get settings;

  Future<void> send(AlertMessage message);

  Future<void> sendTest();

  String? validate();
}

class ChannelDeliveryResult {
  final String channelId;
  final String status;

  ChannelDeliveryResult({required this.channelId, required this.status});
}
