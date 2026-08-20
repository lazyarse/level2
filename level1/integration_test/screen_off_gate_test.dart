import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart' as sql;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:security_cam/sensors/permissions_service.dart';
import 'package:security_cam/state/monitor_controller.dart';
import 'package:security_cam/storage/event_log.dart';
import 'package:security_cam/storage/settings_store.dart';
import 'package:security_cam/storage/snapshot_store.dart';
import 'package:security_cam/ui/app.dart';

/// Screen-off continuity gate: while the FGS runs with `camera|microphone`,
/// the camera analysis + event pipeline must keep working with the screen off.
///
/// The host runner (`tool/run_android_integration_tests.sh`) watches the
/// `[itest] SCREEN_OFF_READY` marker and drives: KEYCODE_POWER (off) ~5s later,
/// then KEYCODE_POWER (on) when `SCREEN_OFF_DONE` is seen. This test asserts
/// monitoring stays healthy through the off window and that a second motion
/// event arrives after the display returns (camera stream survived + resumed).
///
/// Runs with `recordVideo=false` (gateSettings below): the software AVC encoder
/// behind the bound video use case starves the emulator's camera on swiftshader,
/// collapsing analysis to <1 fps within a couple of minutes — while on a real
/// device it's fine, on the headless AOSP image it makes the long gate
/// unreliable. This gate isolates *camera-analysis continuity*; clip recording,
/// export, and resolution are covered by `monitoring_on_device_test.dart`.
void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('monitoring survives a screen-off window', (tester) async {
    sqfliteFfiInit();
    sql.databaseFactory = databaseFactoryFfi;
    SharedPreferences.setMockInitialValues({});
    final settingsStore = await SettingsStore.open();
    await settingsStore
        .save((await settingsStore.load()).copyWith(recordVideo: false));
    final eventLog = await SqliteEventLog.open(inMemoryDatabasePath);
    final snapDir = await Directory.systemTemp.createTemp('scam_itest_soff');
    final snapshotStore = FileSnapshotStore(snapDir.path);
    final controller = MonitorController(
      settingsStore: settingsStore,
      eventRecorder: eventLog,
      snapshotStore: snapshotStore,
      purgeInterval: null,
      permissionsService: const NoopPermissionsService(),
    );
    await controller.init();
    addTearDown(() async {
      await controller.stop();
      await eventLog.close();
      snapDir.deleteSync(recursive: true);
    });
    await binding.setSurfaceSize(const Size(720, 1280));

    await tester.pumpWidget(SecurityCamApp(
      controller: controller,
      eventLoader: () => eventLog.recent(limit: 200),
      snapshotStore: snapshotStore,
    ));
    await tester.pump();

    Future<void> waitForState(MonitorState state) async {
      final deadline = DateTime.now().add(const Duration(minutes: 2));
      while (controller.state != state && DateTime.now().isBefore(deadline)) {
        await tester.pump(const Duration(milliseconds: 500));
      }
      expect(controller.state, state,
          reason: 'controller.error=${controller.error}');
    }

    Future<List<dynamic>> recent() => eventLog.recent();

    await tester.tap(find.byType(FilledButton));
    await waitForState(MonitorState.monitoring);

    // Baseline: first motion event.
    final deadline = DateTime.now().add(const Duration(minutes: 3));
    while ((await recent()).isEmpty && DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(seconds: 2));
    }
    expect((await recent()), isNotEmpty, reason: 'no baseline motion event');
    final baseline = (await recent()).length;
    debugPrint('[itest] SCREEN_OFF_READY');

    // Window during which the host keeps the display off. Assert monitoring
    // stays healthy throughout (no error, state still monitoring).
    var secondEvent = false;
    var windowError = false;
    final windowEnd = DateTime.now().add(const Duration(seconds: 45));
    while (DateTime.now().isBefore(windowEnd)) {
      // Real-time delay, not tester.pump(): while the display is asleep the
      // engine suspends frame production, so pump() blocks indefinitely.
      await Future<void>.delayed(const Duration(seconds: 2));
      if (controller.error != null ||
          controller.state != MonitorState.monitoring) {
        windowError = true;
        break;
      }
      final rows = await recent();
      if (rows.length > baseline) {
        secondEvent = true;
        break;
      }
    }
    debugPrint('[itest] SCREEN_OFF_DONE');

    // Display is back on: the camera must resume full-rate analysis and a
    // second motion event must arrive within the poll window.
    final recoveryDeadline = DateTime.now().add(const Duration(seconds: 90));
    while (!secondEvent && DateTime.now().isBefore(recoveryDeadline)) {
      await Future<void>.delayed(const Duration(seconds: 2));
      final rows = await recent();
      if (rows.length > baseline) secondEvent = true;
    }

    expect(windowError, isFalse,
        reason: 'monitoring stopped/errored during screen-off: '
            '${controller.error}');
    expect(controller.state, MonitorState.monitoring,
        reason: 'monitoring stopped/errored during screen-off: '
            '${controller.error}');
    expect(controller.error, isNull);
    expect(secondEvent, isTrue,
        reason: 'no motion event recorded after the screen-off window '
            '(camera likely stalled)');

    await tester.tap(find.byType(FilledButton));
    await waitForState(MonitorState.idle);
  });
}