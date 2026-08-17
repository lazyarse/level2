import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;

import '../core/channel.dart';
import '../core/models.dart';

class TelegramChannelSettings implements ChannelSettings {
  final String botToken;
  final String chatId;

  const TelegramChannelSettings({required this.botToken, required this.chatId});

  @override
  String get type => 'telegram';

  @override
  Map<String, dynamic> toJson() => {'botToken': botToken, 'chatId': chatId};

  @override
  List<String> get secretFields => ['botToken'];

  factory TelegramChannelSettings.fromJson(Map<String, dynamic> json) =>
      TelegramChannelSettings(
        botToken: json['botToken'] as String? ?? '',
        chatId: json['chatId'] as String? ?? '',
      );
}

class TelegramChannel extends Channel {
  @override
  final String id;
  @override
  final bool enabled;
  @override
  final TelegramChannelSettings settings;
  final http.Client _client;
  static final _tokenRe = RegExp(r'^\d+:[A-Za-z0-9_-]+$');

  TelegramChannel({
    required this.id,
    this.enabled = true,
    required this.settings,
    http.Client? client,
  }) : _client = client ?? http.Client();

  @override
  String get type => 'telegram';

  static String _endpoint(String method, String token) =>
      'https://api.telegram.org/bot$token/$method';

  @override
  Future<void> send(AlertMessage message) async {
    final token = settings.botToken;
    if (message.snapshot != null) {
      final photo = message.snapshot!;
      final photoOk = await _sendPhoto(token, settings.chatId, photo, message.text);
      if (!photoOk) {
        await _sendMessage(token, settings.chatId, message.text);
      }
    } else {
      await _sendMessage(token, settings.chatId, message.text);
    }
  }

  Future<bool> _sendPhoto(
      String token, String chatId, Snapshot photo, String caption) async {
    final request = http.MultipartRequest('POST', Uri.parse(_endpoint('sendPhoto', token)))
      ..fields['chat_id'] = chatId
      ..fields['caption'] = caption
      ..files.add(http.MultipartFile.fromBytes(
        'photo',
        photo.bytes,
        filename: photo.name,
        contentType: MediaType.parse(photo.mimeType),
      ));
    final streamed = await _client.send(request);
    final response = await http.Response.fromStream(streamed);
    return _ok(response);
  }

  Future<void> _sendMessage(String token, String chatId, String text) async {
    final response = await _client.post(
      Uri.parse(_endpoint('sendMessage', token)),
      headers: {'content-type': 'application/json'},
      body: jsonEncode({'chat_id': chatId, 'text': text}),
    );
    if (!_ok(response)) {
      throw StateError('Telegram sendMessage failed (${response.statusCode})');
    }
  }

  bool _ok(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) return false;
    try {
      final body = jsonDecode(response.body) as Map<String, dynamic>;
      return body['ok'] == true;
    } catch (_) {
      return false;
    }
  }

  @override
  Future<void> sendTest() async {
    await _sendMessage(settings.botToken, settings.chatId, 'Security Cam: test alert');
  }

  @override
  String? validate() {
    if (settings.botToken.isEmpty || settings.chatId.isEmpty) {
      return 'Bot token and chat ID are required';
    }
    if (!_tokenRe.hasMatch(settings.botToken)) {
      return 'Bot token is not in the expected format';
    }
    return null;
  }
}
