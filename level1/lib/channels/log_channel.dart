import '../core/channel.dart';
import '../core/models.dart';

class LogChannel extends Channel {
  @override
  final String id;
  @override
  final bool enabled;
  final List<AlertMessage> sent = [];

  LogChannel({this.id = 'log', this.enabled = true});

  @override
  String get type => 'log';

  @override
  ChannelSettings get settings => _LogSettings();

  @override
  Future<void> send(AlertMessage message) async {
    sent.add(message);
  }

  @override
  Future<void> sendTest() async {
    sent.add(AlertMessage(
      timestamp: DateTime.now(),
      triggerType: 'test',
      text: 'Test alert from $id',
    ));
  }

  @override
  String? validate() => null;
}

class _LogSettings implements ChannelSettings {
  @override
  String get type => 'log';

  @override
  Map<String, dynamic> toJson() => const {};

  @override
  List<String> get secretFields => const [];
}
