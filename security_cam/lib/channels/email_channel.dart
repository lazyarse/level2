import 'package:mailer/mailer.dart' as mailer;
import 'package:mailer/smtp_server.dart' as mailer_smtp;

import '../core/channel.dart';
import '../core/models.dart';

class EmailChannelSettings implements ChannelSettings {
  final String host;
  final int port;
  final String username;
  final String password;
  final String from;
  final String to;
  final bool useTls;

  const EmailChannelSettings({
    this.host = '',
    this.port = 587,
    this.username = '',
    this.password = '',
    this.from = '',
    this.to = '',
    this.useTls = false,
  });

  @override
  String get type => 'email';

  @override
  Map<String, dynamic> toJson() => {
        'host': host,
        'port': port,
        'username': username,
        'password': password,
        'from': from,
        'to': to,
        'useTls': useTls,
      };

  @override
  List<String> get secretFields => ['password'];

  factory EmailChannelSettings.fromJson(Map<String, dynamic> json) =>
      EmailChannelSettings(
        host: json['host'] as String? ?? '',
        port: json['port'] as int? ?? 587,
        username: json['username'] as String? ?? '',
        password: json['password'] as String? ?? '',
        from: json['from'] as String? ?? '',
        to: json['to'] as String? ?? '',
useTls: json['useTls'] as bool? ?? false,
    );
}

/// Sends alert emails over SMTP via `package:mailer`. The real transport is
/// replaced by an injectable sender in tests (no live SMTP).
class EmailChannel extends Channel {
  @override
  final String id;
  @override
  final bool enabled;
  @override
  final EmailChannelSettings settings;
  final Future<void> Function(mailer.Message message)? _sender;

  EmailChannel({
    required this.id,
    this.enabled = true,
    required this.settings,
    Future<void> Function(mailer.Message message)? sender,
  }) : _sender = sender;

  @override
  String get type => 'email';

  static final _emailRe = RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$');

  @override
  Future<void> send(AlertMessage message) async {
    final m = mailer.Message()
      ..from = mailer.Address(settings.from)
      ..recipients.add(settings.to)
      ..subject = message.text
      ..text = message.text;
    await (_sender ?? _sendReal)(m);
  }

  @override
  Future<void> sendTest() async {
    final m = mailer.Message()
      ..from = mailer.Address(settings.from)
      ..recipients.add(settings.to)
      ..subject = 'Security Cam: test alert'
      ..text = 'Security Cam: test alert';
    await (_sender ?? _sendReal)(m);
  }

  Future<void> _sendReal(mailer.Message message) async {
    final server = mailer_smtp.SmtpServer(
      settings.host,
      port: settings.port,
      username: settings.username,
      password: settings.password,
      ssl: settings.useTls,
    );
    await mailer.send(message, server);
  }

  @override
  String? validate() {
    if (settings.host.isEmpty) return 'SMTP host is required';
    if (settings.username.isEmpty || settings.password.isEmpty) {
      return 'Username and password are required';
    }
    if (!_emailRe.hasMatch(settings.from)) return 'From address is invalid';
    if (!_emailRe.hasMatch(settings.to)) return 'To address is invalid';
    return null;
  }
}