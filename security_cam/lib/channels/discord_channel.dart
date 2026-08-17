import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;

import '../core/channel.dart';
import '../core/models.dart';

class DiscordChannelSettings implements ChannelSettings {
  final String webhookUrl;

  const DiscordChannelSettings({this.webhookUrl = ''});

  @override
  String get type => 'discord';

  @override
  Map<String, dynamic> toJson() => {'webhookUrl': webhookUrl};

  @override
  List<String> get secretFields => ['webhookUrl'];

  factory DiscordChannelSettings.fromJson(Map<String, dynamic> json) =>
      DiscordChannelSettings(
        webhookUrl: json['webhookUrl'] as String? ?? '',
      );
}

/// Sends alerts to a Discord channel via a webhook URL. The webhook URL
/// carries the auth token, so it's treated as a secret.
class DiscordChannel extends Channel {
  @override
  final String id;
  @override
  final bool enabled;
  @override
  final DiscordChannelSettings settings;
  final http.Client _client;

  DiscordChannel({
    required this.id,
    this.enabled = true,
    required this.settings,
    http.Client? client,
  }) : _client = client ?? http.Client();

  @override
  String get type => 'discord';

  static final _webhookRe = RegExp(
      r'^https://(?:canary|ptb\.)?discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_-]+$');

  @override
  Future<void> send(AlertMessage message) async {
    if (message.snapshot != null) {
      final photo = message.snapshot!;
      final request = http.MultipartRequest('POST', Uri.parse(settings.webhookUrl))
        ..fields['content'] = message.text
        ..files.add(http.MultipartFile.fromBytes(
          'file',
          photo.bytes,
          filename: photo.name,
          contentType: MediaType.parse(photo.mimeType),
        ));
      final streamed = await _client.send(request);
      final response = await http.Response.fromStream(streamed);
      _check(response);
    } else {
      final response = await _client.post(
        Uri.parse(settings.webhookUrl),
        headers: {'content-type': 'application/json'},
        body: jsonEncode({'content': message.text}),
      );
      _check(response);
    }
  }

  void _check(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError(
          'Discord webhook failed (${response.statusCode}) ${response.body}');
    }
  }

  @override
  Future<void> sendTest() async {
    final response = await _client.post(
      Uri.parse(settings.webhookUrl),
      headers: {'content-type': 'application/json'},
      body: jsonEncode({'content': 'Security Cam: test alert'}),
    );
    _check(response);
  }

  @override
  String? validate() {
    final url = settings.webhookUrl.trim();
    if (url.isEmpty) return 'Webhook URL is required';
    if (!_webhookRe.hasMatch(url)) {
      return 'Webhook URL is not a valid Discord webhook URL';
    }
    return null;
  }
}