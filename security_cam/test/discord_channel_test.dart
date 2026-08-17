import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:security_cam/channels/discord_channel.dart';
import 'package:security_cam/core/models.dart';

void main() {
  DiscordChannel channel(http.Client client) {
    return DiscordChannel(
      id: 'discord',
      enabled: true,
      settings: const DiscordChannelSettings(
        webhookUrl:
            'https://discord.com/api/webhooks/12345/abcdefghijk',
      ),
      client: client,
    );
  }

  test('send posts JSON content without a snapshot', () async {
    final requests = <http.Request>[];
    final client = MockClient((request) async {
      requests.add(request);
      return http.Response('{}', 204);
    });
    final c = channel(client);

    await c.send(AlertMessage(
      timestamp: DateTime(2026, 1, 1),
      triggerType: 'motion',
      text: 'Motion detected in Hallway',
    ));

    expect(requests, hasLength(1));
    expect(requests.single.method, 'POST');
    expect(requests.single.headers['content-type'], contains('application/json'));
    expect(requests.single.body, contains('Motion detected in Hallway'));
  });

  test('send uploads the snapshot as a file attachment', () async {
    final requestBodies = <String>[];
    final client = MockClient((request) async {
      requestBodies.add(String.fromCharCodes(request.bodyBytes));
      return http.Response('{}', 204);
    });
    final c = channel(client);

    await c.send(AlertMessage(
      timestamp: DateTime(2026, 1, 1),
      triggerType: 'motion',
      text: 'Motion detected in Hallway',
      snapshot: Snapshot(
        bytes: Uint8List.fromList([1, 2, 3]),
        mimeType: 'image/png',
        name: 'snap.png',
      ),
    ));

    expect(requestBodies, hasLength(1));
    expect(requestBodies.single, contains('snap.png'));
  });

  test('sendTest posts a test alert', () async {
    final bodies = <String>[];
    final client = MockClient((request) async {
      bodies.add(request.body);
      return http.Response('{}', 204);
    });
    final c = channel(client);

    await c.sendTest();

    expect(bodies.single, contains('Security Cam: test alert'));
  });

  test('non-2xx response throws a readable error', () async {
    final client = MockClient((request) async => http.Response('boom', 401));
    final c = channel(client);

    expect(
      () => c.sendTest(),
      throwsA(isA<StateError>().having(
        (e) => e.message,
        'message',
        contains('Discord webhook failed (401)'),
      )),
    );
  });

  test('validate requires a well-formed webhook URL', () {
    expect(
      DiscordChannel(id: 'discord', settings: const DiscordChannelSettings())
          .validate(),
      'Webhook URL is required',
    );
    expect(
      DiscordChannel(
        id: 'discord',
        settings: const DiscordChannelSettings(webhookUrl: 'https://nope.com/x'),
      ).validate(),
      'Webhook URL is not a valid Discord webhook URL',
    );
    expect(channel(MockClient((r) async => http.Response('', 204))).validate(),
        isNull);
  });

  test('webhook URL is a secret field', () {
    expect(
      const DiscordChannelSettings(webhookUrl: 'x').secretFields,
      contains('webhookUrl'),
    );
  });
}