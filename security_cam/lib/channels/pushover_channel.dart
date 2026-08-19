import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;

import '../core/channel.dart';
import '../core/models.dart';

class PushoverChannelSettings implements ChannelSettings {
  final String appToken;
  final String userKey;
  final String sound;
  final int priority;

  const PushoverChannelSettings({
    this.appToken = '',
    this.userKey = '',
    this.sound = '',
    this.priority = 0,
  });

  @override
  String get type => 'pushover';

  @override
  Map<String, dynamic> toJson() => {
        'appToken': appToken,
        'userKey': userKey,
        'sound': sound,
        'priority': priority,
      };

  @override
  List<String> get secretFields => ['appToken', 'userKey'];

  factory PushoverChannelSettings.fromJson(Map<String, dynamic> json) =>
      PushoverChannelSettings(
        appToken: json['appToken'] as String? ?? '',
        userKey: json['userKey'] as String? ?? '',
        sound: json['sound'] as String? ?? '',
        priority: json['priority'] as int? ?? 0,
      );
}

/// Sends alerts to Pushover via the messages.json endpoint. The app token and
/// user key are secrets carried in the request body/fields.
class PushoverChannel extends Channel {
  @override
  final String id;
  @override
  final bool enabled;
  @override
  final PushoverChannelSettings settings;
  final http.Client _client;

  static const _endpoint = 'https://api.pushover.net/1/messages.json';

  PushoverChannel({
    required this.id,
    this.enabled = true,
    required this.settings,
    http.Client? client,
  }) : _client = client ?? http.Client();

  @override
  String get type => 'pushover';

  Map<String, String> _fields(String message) => {
        'token': settings.appToken,
        'user': settings.userKey,
        'message': message,
        if (settings.sound.isNotEmpty) 'sound': settings.sound,
        'priority': settings.priority.toString(),
      };

  @override
  Future<void> send(AlertMessage message) async {
    final snapshot = message.snapshot;
    if (snapshot != null) {
      final request = http.MultipartRequest('POST', Uri.parse(_endpoint))
        ..fields.addAll(_fields(message.text))
        ..files.add(http.MultipartFile.fromBytes(
          'attachment',
          snapshot.bytes,
          filename: snapshot.name,
          contentType: MediaType.parse(snapshot.mimeType),
        ));
      final streamed = await _client.send(request);
      _check(await http.Response.fromStream(streamed));
    } else {
      final response = await _client.post(
        Uri.parse(_endpoint),
        headers: {'content-type': 'application/x-www-form-urlencoded'},
        body: _fields(message.text),
      );
      _check(response);
    }
  }

  @override
  Future<void> sendTest() async {
    await send(AlertMessage(
      timestamp: DateTime.now(),
      triggerType: 'test',
      text: 'Security Cam: test alert',
    ));
  }

  void _check(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError(
          'Pushover failed (${response.statusCode}) ${response.body}');
    }
  }

  @override
  String? validate() {
    if (settings.appToken.isEmpty) return 'App token is required';
    if (settings.userKey.isEmpty) return 'User key is required';
    return null;
  }
}