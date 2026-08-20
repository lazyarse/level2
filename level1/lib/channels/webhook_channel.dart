import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;

import '../core/channel.dart';
import '../core/models.dart';

const webhookPresets = ['discord', 'ntfy', 'slack', 'teams', 'custom'];

class WebhookChannelSettings implements ChannelSettings {
  final String preset;
  final String url;
  final String bearerToken;
  final String title;
  final String bodyStyle;

  const WebhookChannelSettings({
    this.preset = 'custom',
    this.url = '',
    this.bearerToken = '',
    this.title = '',
    this.bodyStyle = 'json',
  });

  @override
  String get type => 'webhook';

  @override
  Map<String, dynamic> toJson() => {
        'preset': preset,
        'url': url,
        'bearerToken': bearerToken,
        'title': title,
        'bodyStyle': bodyStyle,
      };

  @override
  List<String> get secretFields => ['url', 'bearerToken'];

  factory WebhookChannelSettings.fromJson(Map<String, dynamic> json) =>
      WebhookChannelSettings(
        preset: json['preset'] as String? ?? 'custom',
        url: json['url'] as String? ?? '',
        bearerToken: json['bearerToken'] as String? ?? '',
        title: json['title'] as String? ?? '',
        bodyStyle: json['bodyStyle'] as String? ?? 'json',
      );
}

/// Sends alerts to a generic webhook URL. The [preset] selects the request
/// shape (discord multipart/JSON, ntfy text/plain, slack/teams JSON, custom
/// JSON or text). The webhook URL and bearer token carry the auth secrets.
class WebhookChannel extends Channel {
  @override
  final String id;
  @override
  final bool enabled;
  @override
  final WebhookChannelSettings settings;
  final http.Client _client;

  WebhookChannel({
    required this.id,
    this.enabled = true,
    required this.settings,
    http.Client? client,
  }) : _client = client ?? http.Client();

  @override
  String get type => 'webhook';

  static final _discordRe = RegExp(
      r'^https://(?:canary|ptb\.)?discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_-]+$');
  static final _slackRe = RegExp(
      r'^https://hooks\.slack\.com/services/T\d+/B\d+/[A-Za-z0-9]+$');
  static final _teamsRe = RegExp(
      r'^https://[A-Za-z0-9.\-]+\.webhook\.office\.com/webhookbot/.+$');

  @override
  Future<void> send(AlertMessage message) async {
    switch (settings.preset) {
      case 'discord':
        await _sendDiscord(message);
      case 'ntfy':
        await _sendNtfy(message);
      case 'slack':
        await _sendJson({'text': message.text});
      case 'teams':
        await _sendJson({'text': message.text});
      default:
        await _sendCustom(message);
    }
  }

  Future<void> _sendDiscord(AlertMessage message) async {
    final snapshot = message.snapshot;
    if (snapshot != null) {
      final request = http.MultipartRequest('POST', Uri.parse(settings.url))
        ..fields['content'] = message.text
        ..files.add(http.MultipartFile.fromBytes(
          'file',
          snapshot.bytes,
          filename: snapshot.name,
          contentType: MediaType.parse(snapshot.mimeType),
        ));
      final streamed = await _client.send(request);
      final response = await http.Response.fromStream(streamed);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        await _sendJson({'content': message.text});
        return;
      }
    } else {
      await _sendJson({'content': message.text});
    }
  }

  Future<void> _sendNtfy(AlertMessage message) async {
    final headers = <String, String>{'content-type': 'text/plain'};
    if (settings.bearerToken.isNotEmpty) {
      headers['Authorization'] = 'Bearer ${settings.bearerToken}';
    }
    if (settings.title.isNotEmpty) {
      headers['X-Title'] = settings.title;
    }
    await _post(headers, message.text);
  }

  Future<void> _sendCustom(AlertMessage message) async {
    final headers = <String, String>{};
    if (settings.bearerToken.isNotEmpty) {
      headers['Authorization'] = 'Bearer ${settings.bearerToken}';
    }
    if (settings.bodyStyle == 'text') {
      headers['content-type'] = 'text/plain';
      await _post(headers, message.text);
    } else {
      await _sendJson({'text': message.text});
    }
  }

  Future<void> _sendJson(Map<String, Object?> body) async {
    await _post(
        {'content-type': 'application/json'}, jsonEncode(body));
  }

  Future<void> _post(Map<String, String> headers, String body) async {
    final response = await _client.post(Uri.parse(settings.url),
        headers: headers, body: body);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError(
          'Webhook failed (${response.statusCode}) ${response.body}');
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

  @override
  String? validate() {
    final url = settings.url.trim();
    if (url.isEmpty) return 'Webhook URL is required';
    if (!url.startsWith('https://')) return 'Webhook URL must be https';
    switch (settings.preset) {
      case 'discord':
        if (!_discordRe.hasMatch(url)) {
          return 'Webhook URL is not a valid Discord webhook URL';
        }
      case 'slack':
        if (!_slackRe.hasMatch(url)) {
          return 'Webhook URL is not a valid Slack incoming webhook URL';
        }
      case 'teams':
        if (!_teamsRe.hasMatch(url)) {
          return 'Webhook URL is not a valid Teams webhook URL';
        }
      case 'ntfy':
        final rest = url.substring('https://'.length);
        if (!rest.contains('/')) return 'ntfy topic is missing from the URL';
    }
    return null;
  }
}