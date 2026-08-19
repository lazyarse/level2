import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:security_cam/channels/email_channel.dart';
import 'package:security_cam/channels/pushover_channel.dart';
import 'package:security_cam/channels/webhook_channel.dart';
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
  testWidgets('renders email, webhook, and pushover channel fields',
      (tester) async {
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
    expect(find.text('discord'), findsOneWidget,
        reason: 'preset dropdown defaults to the discord preset');
    await tester.scrollUntilVisible(
        find.text('App token'), 300, scrollable: _listScrollable);
    expect(find.text('App token'), findsOneWidget);
    expect(find.text('User key'), findsOneWidget);
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

  testWidgets('save persists email and webhook channel settings',
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

    final preset = find.byKey(const ValueKey('webhookPreset_discord'));
    await tester.scrollUntilVisible(preset, 300, scrollable: _listScrollable);
    await tester.tap(preset);
    await tester.pumpAndSettle();
    await tester.tap(find.text('slack').last);
    await tester.pumpAndSettle();

    final save = find.text('Save settings');
    await tester.scrollUntilVisible(save, 300, scrollable: _listScrollable);
    await tester.tap(save);
    await tester.pumpAndSettle();

    final types =
        controller.settings.channelConfigs.map((c) => c.type).toList();
    expect(
        types, containsAll(['log', 'telegram', 'email', 'webhook', 'pushover']));

    final email = controller.settings.channelConfigs
        .firstWhere((c) => c.type == 'email');
    final es = EmailChannelSettings.fromJson(email.settingsJson);
    expect(es.host, 'smtp.example.com');
    expect(es.port, 587);
    expect(es.to, 'alice@example.com');

    final webhook = controller.settings.channelConfigs
        .firstWhere((c) => c.type == 'webhook');
    final ws = WebhookChannelSettings.fromJson(webhook.settingsJson);
    expect(ws.preset, 'slack');
    expect(ws.url, 'https://discord.com/api/webhooks/1/abc');

    expect(controller.settings.retentionDays, greaterThanOrEqualTo(0));
  });

  testWidgets('save persists pushover settings', (tester) async {
    final controller = await _controller();
    addTearDown(controller.dispose);
    await _pump(tester, controller);

    await tester.scrollUntilVisible(
        find.text('App token'), 300, scrollable: _listScrollable);
    await tester.enterText(_fieldOf('App token'), 'apptok123');
    await tester.enterText(_fieldOf('User key'), 'userkey456');

    final save = find.text('Save settings');
    await tester.scrollUntilVisible(save, 300, scrollable: _listScrollable);
    await tester.tap(save);
    await tester.pumpAndSettle();

    final pushover = controller.settings.channelConfigs
        .firstWhere((c) => c.type == 'pushover');
    final ps = PushoverChannelSettings.fromJson(pushover.settingsJson);
    expect(ps.appToken, 'apptok123');
    expect(ps.userKey, 'userkey456');
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

  testWidgets('resolution dropdown saves the recording quality',
      (tester) async {
    final controller = await _controller();
    addTearDown(controller.dispose);
    await _pump(tester, controller);

    final dropdown = find.widgetWithText(DropdownButtonFormField<String>,
        'Lowest (device minimum)');
    await tester.scrollUntilVisible(dropdown, 300, scrollable: _listScrollable);
    expect(controller.settings.videoQuality, 'lowest');

    await tester.tap(dropdown);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Full HD (1080p)').last);
    await tester.pumpAndSettle();

    final save = find.text('Save settings');
    await tester.scrollUntilVisible(save, 300, scrollable: _listScrollable);
    await tester.tap(save);
    await tester.pumpAndSettle();

    expect(controller.settings.videoQuality, 'fhd');
  });

  testWidgets('resolution dropdown and roll sliders disable when recording off',
      (tester) async {
    final controller = await _controller();
    addTearDown(controller.dispose);
    await _pump(tester, controller);

    final toggle = find.widgetWithText(SwitchListTile, 'Record video locally');
    await tester.scrollUntilVisible(toggle, 300, scrollable: _listScrollable);
    await tester.tap(toggle);
    await tester.pumpAndSettle();

    final dropdown = find.byKey(const ValueKey('videoQualityDropdown'));
    expect(
      tester.widget<DropdownButtonFormField<String>>(dropdown).onChanged,
      isNull,
      reason: 'resolution dropdown should be disabled when recording is off',
    );
    for (final key in const [
      ValueKey('preRollSlider'),
      ValueKey('postRollSlider'),
    ]) {
      final slider = find.byKey(key);
      expect(
        tester.widget<Slider>(slider).onChanged,
        isNull,
        reason: '$key should be disabled when recording is off',
      );
    }
  });

  testWidgets('face detector card shows motion-gated toggle', (tester) async {
    final controller = await _controller();
    addTearDown(controller.dispose);
    await _pump(tester, controller);

    await tester.scrollUntilVisible(
        find.text('Face'), 300, scrollable: _listScrollable);
    await tester.tap(find.text('Face'));
    await tester.pumpAndSettle();
    expect(find.text('Motion-gated'), findsOneWidget);
  });

  testWidgets('advanced section exposes analysis resolution', (tester) async {
    final controller = await _controller();
    addTearDown(controller.dispose);
    await _pump(tester, controller);

    await tester.scrollUntilVisible(
        find.text('Advanced'), 300, scrollable: _listScrollable);
    expect(find.text('Advanced'), findsOneWidget);
    await tester.scrollUntilVisible(
        find.text('Balanced (320x240)'), 300, scrollable: _listScrollable);
    expect(find.text('Balanced (320x240)'), findsOneWidget);
  });
}