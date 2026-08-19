import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:security_cam/channels/webhook_channel.dart';
import 'package:security_cam/core/models.dart';

void main() {
  const discordUrl = 'https://discord.com/api/webhooks/12345/abcdefghijk';

  WebhookChannel channel(
    http.Client client, {
    String preset = 'discord',
    String url = discordUrl,
    String bearerToken = '',
    String title = '',
    String bodyStyle = 'json',
  }) {
    return WebhookChannel(
      id: 'webhook',
      enabled: true,
      settings: WebhookChannelSettings(
        preset: preset,
        url: url,
        bearerToken: bearerToken,
        title: title,
        bodyStyle: bodyStyle,
      ),
      client: client,
    );
  }

  AlertMessage message({Snapshot? snapshot}) => AlertMessage(
        timestamp: DateTime(2026, 1, 1),
        triggerType: 'motion',
        text: 'Motion detected in Hallway',
        snapshot: snapshot,
      );

  Snapshot snapshot() => Snapshot(
        bytes: Uint8List.fromList([1, 2, 3]),
        mimeType: 'image/png',
        name: 'snap.png',
      );

  group('discord preset', () {
    test('send posts JSON content without a snapshot', () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        return http.Response('{}', 204);
      });
      final c = channel(client);

      await c.send(message());

      expect(requests, hasLength(1));
      expect(requests.single.method, 'POST');
      expect(
          requests.single.headers['content-type'], contains('application/json'));
      expect(requests.single.body, contains('Motion detected in Hallway'));
    });

    test('send uploads the snapshot as a file attachment', () async {
      final requestBodies = <String>[];
      final client = MockClient((request) async {
        requestBodies.add(String.fromCharCodes(request.bodyBytes));
        return http.Response('{}', 204);
      });
      final c = channel(client);

      await c.send(message(snapshot: snapshot()));

      expect(requestBodies, hasLength(1));
      expect(requestBodies.single, contains('snap.png'));
    });

    test('upload non-2xx falls back to a JSON content-only request', () async {
      final bodies = <String>[];
      var calls = 0;
      final client = MockClient((request) async {
        calls++;
        if (calls == 1) return http.Response('boom', 401);
        bodies.add(String.fromCharCodes(request.bodyBytes));
        return http.Response('{}', 204);
      });
      final c = channel(client);

      await c.send(message(snapshot: snapshot()));

      expect(bodies, hasLength(1));
      expect(bodies.single, contains('Motion detected in Hallway'));
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
          contains('Webhook failed (401)'),
        )),
      );
    });

    test('validate requires a well-formed Discord webhook URL', () {
      expect(
        WebhookChannel(id: 'w', settings: const WebhookChannelSettings(preset: 'discord'))
            .validate(),
        'Webhook URL is required',
      );
      expect(
        WebhookChannel(
          id: 'w',
          settings: const WebhookChannelSettings(
              preset: 'discord', url: 'https://nope.com/x'),
        ).validate(),
        'Webhook URL is not a valid Discord webhook URL',
      );
      expect(channel(MockClient((r) async => http.Response('', 204))).validate(),
          isNull);
    });
  });

  group('ntfy preset', () {
    test('posts text/plain with optional bearer and title', () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        return http.Response('', 200);
      });
      final c = channel(client,
          preset: 'ntfy',
          url: 'https://ntfy.sh/mytopic',
          bearerToken: 'tok123',
          title: 'My Alert');

      await c.send(message());

      expect(requests.single.method, 'POST');
      expect(requests.single.headers['content-type'], contains('text/plain'));
      expect(requests.single.headers['Authorization'], 'Bearer tok123');
      expect(requests.single.headers['X-Title'], 'My Alert');
      expect(requests.single.body, 'Motion detected in Hallway');
    });

    test('omits bearer and title headers when unset', () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        return http.Response('', 200);
      });
      final c = channel(client, preset: 'ntfy', url: 'https://ntfy.sh/mytopic');

      await c.send(message());

      expect(requests.single.headers.containsKey('Authorization'), isFalse);
      expect(requests.single.headers.containsKey('X-Title'), isFalse);
    });
  });

  group('slack preset', () {
    test('posts JSON text body', () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        return http.Response('', 200);
      });
      final c = channel(client,
          preset: 'slack',
          url: 'https://hooks.slack.com/services/T123/B456/abc');

      await c.send(message());

      expect(requests.single.method, 'POST');
      expect(
          requests.single.headers['content-type'], contains('application/json'));
      expect(requests.single.body, contains('Motion detected in Hallway'));
    });
  });

  group('teams preset', () {
    test('posts JSON text body', () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        return http.Response('', 200);
      });
      final c = channel(client,
          preset: 'teams',
          url: 'https://example.webhook.office.com/webhookbot/xxx');

      await c.send(message());

      expect(requests.single.method, 'POST');
      expect(
          requests.single.headers['content-type'], contains('application/json'));
      expect(requests.single.body, contains('Motion detected in Hallway'));
    });
  });

  group('custom preset', () {
    test('json body style posts {"text": ...}', () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        return http.Response('', 200);
      });
      final c = channel(client,
          preset: 'custom', url: 'https://example.com/hook', bodyStyle: 'json');

      await c.send(message());

      expect(requests.single.body, contains('Motion detected in Hallway'));
      expect(
          requests.single.headers['content-type'], contains('application/json'));
    });

    test('text body style posts the raw text with bearer', () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        return http.Response('', 200);
      });
      final c = channel(client,
          preset: 'custom',
          url: 'https://example.com/hook',
          bodyStyle: 'text',
          bearerToken: 'bear');

      await c.send(message());

      expect(requests.single.headers['content-type'], contains('text/plain'));
      expect(requests.single.headers['Authorization'], 'Bearer bear');
      expect(requests.single.body, 'Motion detected in Hallway');
    });
  });

  group('validate per preset', () {
    test('slack rejects a Discord-shaped URL', () {
      final c = WebhookChannel(
        id: 'w',
        settings: const WebhookChannelSettings(
            preset: 'slack', url: discordUrl),
      );
      expect(c.validate(), 'Webhook URL is not a valid Slack incoming webhook URL');
    });

    test('teams rejects a non-office webhook URL', () {
      final c = WebhookChannel(
        id: 'w',
        settings: const WebhookChannelSettings(
            preset: 'teams', url: 'https://example.com/hook'),
      );
      expect(c.validate(), 'Webhook URL is not a valid Teams webhook URL');
    });

    test('ntfy requires a topic in the URL', () {
      final noTopic = WebhookChannel(
        id: 'w',
        settings: const WebhookChannelSettings(
            preset: 'ntfy', url: 'https://ntfy.sh'),
      );
      expect(noTopic.validate(), 'ntfy topic is missing from the URL');

      final withTopic = WebhookChannel(
        id: 'w',
        settings: const WebhookChannelSettings(
            preset: 'ntfy', url: 'https://ntfy.sh/mytopic'),
      );
      expect(withTopic.validate(), isNull);
    });

    test('custom accepts any https URL and rejects http', () {
      final ok = WebhookChannel(
        id: 'w',
        settings: const WebhookChannelSettings(
            preset: 'custom', url: 'https://example.com/x'),
      );
      expect(ok.validate(), isNull);

      final httpUrl = WebhookChannel(
        id: 'w',
        settings: const WebhookChannelSettings(
            preset: 'custom', url: 'http://example.com/x'),
      );
      expect(httpUrl.validate(), 'Webhook URL must be https');
    });
  });

  test('url and bearerToken are secret fields', () {
    expect(
      const WebhookChannelSettings(preset: 'ntfy', url: 'x', bearerToken: 'y')
          .secretFields,
      containsAll(['url', 'bearerToken']),
    );
  });
}