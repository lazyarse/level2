import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:security_cam/channels/pushover_channel.dart';
import 'package:security_cam/core/models.dart';

void main() {
  PushoverChannel channel(http.Client client) {
    return PushoverChannel(
      id: 'pushover',
      enabled: true,
      settings: const PushoverChannelSettings(
        appToken: 'apptok',
        userKey: 'userkey',
        sound: 'siren',
        priority: 1,
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

  test('send posts form-encoded fields without a snapshot', () async {
    final requests = <http.Request>[];
    final client = MockClient((request) async {
      requests.add(request);
      return http.Response('{}', 200);
    });
    final c = channel(client);

    await c.send(message());

    expect(requests, hasLength(1));
    expect(requests.single.method, 'POST');
    expect(requests.single.headers['content-type'],
        contains('application/x-www-form-urlencoded'));
    expect(requests.single.body, contains('token=apptok'));
    expect(requests.single.body, contains('user=userkey'));
    expect(requests.single.body, contains('message=Motion'));
    expect(requests.single.body, contains('sound=siren'));
    expect(requests.single.body, contains('priority=1'));
    expect(requests.single.body, isNot(contains('attachment')));
  });

  test('send uploads the snapshot as an attachment', () async {
    final requestBodies = <String>[];
    final client = MockClient((request) async {
      requestBodies.add(String.fromCharCodes(request.bodyBytes));
      return http.Response('{}', 200);
    });
    final c = channel(client);

    await c.send(message(snapshot: snapshot()));

    expect(requestBodies, hasLength(1));
    expect(requestBodies.single, contains('attachment'));
    expect(requestBodies.single, contains('snap.png'));
  });

  test('sendTest posts a test alert', () async {
    final bodies = <String>[];
    final client = MockClient((request) async {
      bodies.add(request.body);
      return http.Response('{}', 200);
    });
    final c = channel(client);

    await c.sendTest();

    final fields = Uri.splitQueryString(bodies.single);
    expect(fields['message'], 'Security Cam: test alert');
    expect(fields['token'], 'apptok');
  });

  test('non-2xx response throws a readable error', () async {
    final client = MockClient((request) async => http.Response('boom', 401));
    final c = channel(client);

    expect(
      () => c.sendTest(),
      throwsA(isA<StateError>().having(
        (e) => e.message,
        'message',
        contains('Pushover failed (401)'),
      )),
    );
  });

  test('validate requires app token and user key', () {
    expect(
      PushoverChannel(id: 'p', settings: const PushoverChannelSettings())
          .validate(),
      'App token is required',
    );
    expect(
      PushoverChannel(
        id: 'p',
        settings: const PushoverChannelSettings(appToken: 'a'),
      ).validate(),
      'User key is required',
    );
    expect(channel(MockClient((r) async => http.Response('', 200))).validate(),
        isNull);
  });

  test('appToken and userKey are secret fields', () {
    expect(
      const PushoverChannelSettings(appToken: 'a', userKey: 'u').secretFields,
      ['appToken', 'userKey'],
    );
  });
}