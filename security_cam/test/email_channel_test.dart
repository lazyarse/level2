import 'package:flutter_test/flutter_test.dart';
import 'package:mailer/mailer.dart' as mailer;
import 'package:security_cam/channels/email_channel.dart';
import 'package:security_cam/core/models.dart';

void main() {
  EmailChannel channel({Future<void> Function(mailer.Message)? sender}) {
    return EmailChannel(
      id: 'email',
      enabled: true,
      settings: const EmailChannelSettings(
        host: 'smtp.example.com',
        port: 587,
        username: 'alice',
        password: 'secret',
        from: 'alice@example.com',
        to: 'bob@example.com',
      ),
      sender: sender,
    );
  }

  test('send delivers the alert text as the message body', () async {
    final sent = <mailer.Message>[];
    final c = channel(sender: (m) async => sent.add(m));

    await c.send(AlertMessage(
      timestamp: DateTime(2026, 1, 1),
      triggerType: 'motion',
      text: 'Motion detected in Hallway',
    ));

    expect(sent, hasLength(1));
    expect(sent.single.from!.mailAddress, 'alice@example.com');
    expect(sent.single.recipients, ['bob@example.com']);
    expect(sent.single.subject, 'Motion detected in Hallway');
    expect(sent.single.text, 'Motion detected in Hallway');
  });

  test('sendTest delivers a test message', () async {
    final sent = <mailer.Message>[];
    final c = channel(sender: (m) async => sent.add(m));

    await c.sendTest();

    expect(sent.single.subject, 'Security Cam: test alert');
  });

  test('validate requires host, credentials and valid addresses', () {
    expect(
      EmailChannel(id: 'email', settings: const EmailChannelSettings())
          .validate(),
      'SMTP host is required',
    );
    expect(
      channel().validate(),
      isNull,
    );
    expect(
      EmailChannel(
        id: 'email',
        settings: const EmailChannelSettings(
          host: 'smtp.example.com',
          username: 'alice',
          password: 'secret',
          from: 'not-an-email',
          to: 'bob@example.com',
        ),
      ).validate(),
      'From address is invalid',
    );
  });

  test('secretFields hides the password', () {
    expect(
      const EmailChannelSettings(password: 'x').secretFields,
      contains('password'),
    );
  });
}