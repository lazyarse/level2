import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:security_cam/channels/telegram_channel.dart';
import 'package:security_cam/core/models.dart';

void main() {
  TelegramChannel channel({http.Client? client}) => TelegramChannel(
        id: 'telegram',
        settings: const TelegramChannelSettings(
          botToken: '123456:ABC-DEF',
          chatId: '42',
        ),
        client: client,
      );

  test('validate accepts well-formed token', () {
    expect(channel().validate(), isNull);
  });

  test('validate rejects empty or malformed token', () {
    final bad = TelegramChannel(
      id: 'telegram',
      settings: const TelegramChannelSettings(botToken: 'nope', chatId: '42'),
    );
    expect(bad.validate(), isNotNull);
    final empty = TelegramChannel(
      id: 'telegram',
      settings: const TelegramChannelSettings(botToken: '', chatId: ''),
    );
    expect(empty.validate(), isNotNull);
  });

  test('send posts text-only message when no snapshot', () async {
    late http.Request captured;
    final client = MockClient((request) async {
      captured = request;
      return http.Response(jsonEncode({'ok': true}), 200);
    });
    final channel_ = channel(client: client);
    await channel_.send(AlertMessage(
      timestamp: DateTime(2026, 1, 1),
      triggerType: TriggerType.motion,
      text: 'Motion detected in Hallway at 2026-01-01T00:00:00.000',
    ));

    expect(captured.url.path, '/bot123456:ABC-DEF/sendMessage');
    final body = jsonDecode(captured.body) as Map<String, dynamic>;
    expect(body['chat_id'], '42');
    expect(body['text'], contains('Motion detected in Hallway'));
  });

  test('send falls back to text when photo fails', () async {
    final requests = <http.Request>[];
    final client = MockClient((request) async {
      requests.add(request);
      if (request.url.path.endsWith('/sendPhoto')) {
        return http.Response(jsonEncode({'ok': false}), 200);
      }
      return http.Response(jsonEncode({'ok': true}), 200);
    });
    final channel_ = channel(client: client);
    await channel_.send(AlertMessage(
      timestamp: DateTime(2026, 1, 1),
      triggerType: TriggerType.motion,
      text: 'Motion detected',
      snapshot: Snapshot(
        bytes: Uint8List(0),
        mimeType: 'image/png',
        name: 'snap.png',
      ),
    ));

    expect(requests, hasLength(2));
    expect(requests[1].url.path, '/bot123456:ABC-DEF/sendMessage');
  });

  test('send throws on non-ok text response', () async {
    final client = MockClient((request) async {
      return http.Response(jsonEncode({'ok': false}), 400);
    });
    final channel_ = channel(client: client);
    expect(
      () => channel_.send(AlertMessage(
        timestamp: DateTime(2026, 1, 1),
        triggerType: TriggerType.motion,
        text: 'Motion detected',
      )),
      throwsA(isA<StateError>()),
    );
  });
}