import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:security_cam/channels/discord_channel.dart';
import 'package:security_cam/channels/email_channel.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/sensors/permissions_service.dart';
import 'package:security_cam/state/monitor_controller.dart';
import 'package:security_cam/storage/event_recorder.dart';
import 'package:security_cam/storage/settings_store.dart';
import 'package:security_cam/storage/snapshot_store.dart';
import 'package:security_cam/ui/settings_screen.dart';

class _FakeRecorder implements EventRecorder {
  @override
  Future<void> record(RecordedEvent event) async {}

  @override
  Future<DeletedMedia> deleteEvents({DateTime? olderThan}) async =>
      const DeletedMedia();
}

class _FakeStore implements SnapshotStore {
  @override
  Future<void> delete(String name) async {}

  @override
  Future<Snapshot?> load(String name) async => null;

  @override
  Future<String> save(Snapshot snapshot) async => snapshot.name;
}

Future<MonitorController> _controller() async {
  SharedPreferences.setMockInitialValues({});
  final settingsStore = await SettingsStore.open();
  final controller = MonitorController(
    settingsStore: settingsStore,
    eventRecorder: _FakeRecorder(),
    snapshotStore: _FakeStore(),
    purgeInterval: null,
    permissionsService: const NoopPermissionsService(),
  );
  await controller.init();
  return controller;
}

Future<void> _pump(WidgetTester tester, MonitorController controller) async {
  tester.view.physicalSize = const Size(800, 2400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(body: SettingsScreen(controller: controller)),
  ));
  await tester.pumpAndSettle();
}

Finder _fieldOf(String label) =>
    find.ancestor(of: find.text(label), matching: find.byType(TextField));

final Finder _listScrollable = find
    .descendant(of: find.byType(ListView), matching: find.byType(Scrollable))
    .first;

void main() {
  testWidgets('renders email and discord channel fields', (tester) async {
    final controller = await _controller();
    addTearDown(controller.dispose);
    await _pump(tester, controller);

    await tester.scrollUntilVisible(
        find.text('SMTP host'), 300, scrollable: _listScrollable);
    expect(find.text('SMTP host'), findsOneWidget);
    expect(find.text('Port (587 or 465)'), findsOneWidget);
    expect(find.text('Username'), findsOneWidget);
    expect(find.text('Password / app password'), findsOneWidget);
    expect(find.text('From address'), findsOneWidget);
    expect(find.text('To address'), findsOneWidget);
    await tester.scrollUntilVisible(
        find.text('Webhook URL'), 300, scrollable: _listScrollable);
    expect(find.text('Webhook URL'), findsOneWidget);
  });

  testWidgets('log channel is hidden from Channels but routable', (tester) async {
    final controller = await _controller();
    addTearDown(controller.dispose);
    await _pump(tester, controller);

    await tester.scrollUntilVisible(
        find.text('SMTP host'), 300, scrollable: _listScrollable);
    expect(find.widgetWithText(SwitchListTile, 'log'), findsNothing,
        reason: 'log is internal plumbing, not a user-toggleable channel');

    await tester.drag(_listScrollable, const Offset(0, 1500));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(CheckboxListTile, 'log'), findsWidgets,
        reason: 'detectors can still route to the log');
  });

  testWidgets('save persists email and discord channel settings',
      (tester) async {
    final controller = await _controller();
    addTearDown(controller.dispose);
    await _pump(tester, controller);

    await tester.scrollUntilVisible(
        find.text('SMTP host'), 300, scrollable: _listScrollable);
    await tester.enterText(_fieldOf('SMTP host'), 'smtp.example.com');
    await tester.enterText(_fieldOf('Port (587 or 465)'), '587');
    await tester.enterText(_fieldOf('To address'), 'alice@example.com');
    await tester.scrollUntilVisible(
        find.text('Webhook URL'), 300, scrollable: _listScrollable);
    await tester.enterText(_fieldOf('Webhook URL'),
        'https://discord.com/api/webhooks/1/abc');

    final save = find.text('Save settings');
    await tester.scrollUntilVisible(save, 300, scrollable: _listScrollable);
    await tester.tap(save);
    await tester.pumpAndSettle();

    final types =
        controller.settings.channelConfigs.map((c) => c.type).toList();
    expect(types, containsAll(['log', 'telegram', 'email', 'discord']));

    final email = controller.settings.channelConfigs
        .firstWhere((c) => c.type == 'email');
    final es = EmailChannelSettings.fromJson(email.settingsJson);
    expect(es.host, 'smtp.example.com');
    expect(es.port, 587);
    expect(es.to, 'alice@example.com');

    final discord = controller.settings.channelConfigs
        .firstWhere((c) => c.type == 'discord');
    final ds = DiscordChannelSettings.fromJson(discord.settingsJson);
    expect(ds.webhookUrl, 'https://discord.com/api/webhooks/1/abc');

    expect(controller.settings.retentionDays, greaterThanOrEqualTo(0));
  });

  testWidgets('record video toggle saves the video clip preference',
      (tester) async {
    final controller = await _controller();
    addTearDown(controller.dispose);
    await _pump(tester, controller);

    final toggle = find.widgetWithText(SwitchListTile, 'Record video locally');
    await tester.scrollUntilVisible(toggle, 300, scrollable: _listScrollable);
    expect(controller.settings.recordVideo, isTrue);

    await tester.tap(toggle);
    await tester.pumpAndSettle();

    final save = find.text('Save settings');
    await tester.scrollUntilVisible(save, 300, scrollable: _listScrollable);
    await tester.tap(save);
    await tester.pumpAndSettle();

    expect(controller.settings.recordVideo, isFalse);
  });
}