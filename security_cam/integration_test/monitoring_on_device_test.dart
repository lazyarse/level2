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

/// Shared on-device harness for the Android integration suite.
///
/// These tests drive the real `camera_service` module (CameraX + FGS + mic +
/// YAMNet) on an emulator/device. The host runner
/// (`tool/run_android_integration_tests.sh`) pre-grants the system permissions
/// (CAMERA / RECORD_AUDIO / POST_NOTIFICATIONS) via `pm grant`; the controller
/// is constructed with a [NoopPermissionsService] so the pipeline never blocks
/// on a system dialog, while the native stack still enforces the real granted
/// state. The permission-dialog UX itself is verified once by hand (B9.4) and
/// the Dart-side gate is covered by unit + the [FakePermissionsService] test.
class DeviceHarness {
  DeviceHarness._();

  static int _nextId = 0;

  late final SettingsStore settingsStore;
  late final SqliteEventLog eventLog;
  late final Directory snapDir;
  late final FileSnapshotStore snapshotStore;
  late final MonitorController controller;
  late final Future<List<RecordedEventRow>> Function() eventLoader;

  /// How long to keep polling for the first motion trigger. The emulator
  /// virtual camera scene moves continuously, but YAMNet/UI load is slow in
  /// debug/profile on swiftshader emulators.
  static const pollTimeout = Duration(minutes: 3);
  static const pollInterval = Duration(seconds: 2);

  static Future<DeviceHarness> create({PermissionsService? permissions}) async {
    sqfliteFfiInit();
    sql.databaseFactory = databaseFactoryFfi;
    SharedPreferences.setMockInitialValues({});
    final harness = DeviceHarness._();
    harness.settingsStore = await SettingsStore.open();
    // sqflite caches database instances by path, so each harness must use a
    // unique in-memory URI or a later harness would receive a closed instance.
    final memDb = 'file:itest_$_nextId?mode=memory&cache=shared';
    _nextId++;
    harness.eventLog = await SqliteEventLog.open(memDb);
    harness.snapDir =
        await Directory.systemTemp.createTemp('scam_itest_snaps');
    harness.snapshotStore = FileSnapshotStore(harness.snapDir.path);
    harness.controller = MonitorController(
      settingsStore: harness.settingsStore,
      eventRecorder: harness.eventLog,
      snapshotStore: harness.snapshotStore,
      purgeInterval: null,
      permissionsService: permissions ?? const NoopPermissionsService(),
    );
    await harness.controller.init();
    harness.eventLoader = () => harness.eventLog.recent(limit: 200);
    return harness;
  }

  Widget buildApp() => SecurityCamApp(
        controller: controller,
        eventLoader: eventLoader,
        snapshotStore: snapshotStore,
      );

  Future<void> close() async {
    await eventLog.close();
    snapDir.deleteSync(recursive: true);
  }

  /// Polls `recent()` until a row matching [predicate] appears or [timeout].
  Future<RecordedEventRow?> waitForEvent(
    bool Function(RecordedEventRow) predicate, {
    Duration timeout = pollTimeout,
  }) async {
    final deadline = DateTime.now().add(timeout);
    while (DateTime.now().isBefore(deadline)) {
      final rows = await eventLog.recent();
      for (final row in rows) {
        if (predicate(row)) return row;
      }
      await Future<void>.delayed(pollInterval);
    }
    return null;
  }
}

/// Test-level fake permission service for the deny gate (avoids the system
/// permission dialog, which Flutter tests cannot interact with).
class FakePermissionsService extends PermissionsService {
  const FakePermissionsService({
    this.cameraGranted = true,
    this.microphoneGranted = true,
  });

  final bool cameraGranted;
  final bool microphoneGranted;

  @override
  Future<PermissionsResult> ensurePermissions() async => PermissionsResult(
        cameraGranted: cameraGranted,
        microphoneGranted: microphoneGranted,
        notificationsGranted: true,
      );
}

/// Taps the monitor Start/Stop button and waits for [MonitorController] to
/// reach [state].
Future<void> tapMonitorButtonAndAwait(
  WidgetTester tester,
  MonitorController controller,
  MonitorState state,
) async {
  await tester.tap(find.byType(FilledButton));
  final deadline = DateTime.now().add(const Duration(minutes: 2));
  while (controller.state != state && DateTime.now().isBefore(deadline)) {
    await tester.pump(const Duration(milliseconds: 500));
  }
  expect(controller.state, state,
      reason: 'controller.error=${controller.error}');
}

/// Prints a marker the host runner parses from logcat to coordinate the
/// screen-off / wake sequence in [screen_off_gate_test].
void mark(String name) => debugPrint('[itest] $name');

/// One-time binding init for the whole suite.
// ignore: unused_element
final IntegrationTestWidgetsFlutterBinding _binding =
    IntegrationTestWidgetsFlutterBinding.ensureInitialized();

DeviceHarness? _shared;
DeviceHarness get harness => _shared!;

void main() {
  setUpAll(() async {
    _shared = await DeviceHarness.create();
  });

  tearDownAll(() async {
    await harness.controller.stop();
    await harness.close();
  });

  group('Android device monitoring', () {
    testWidgets('permission gate blocks start when denied', (tester) async {
      final denied = await DeviceHarness.create(
        permissions: const FakePermissionsService(
          cameraGranted: false,
          microphoneGranted: true,
        ),
      );
      addTearDown(denied.close);
      await tester.pumpWidget(denied.buildApp());
      await tester.pump();

      await tapMonitorButtonAndAwait(
          tester, denied.controller, MonitorState.error);
      expect(denied.controller.error, contains('permissions'));
    });

    testWidgets(
      'full monitoring run: start, motion event + snapshot, stop',
      (tester) async {
        await tester.pumpWidget(harness.buildApp());
        await tester.pump();

        await tapMonitorButtonAndAwait(
            tester, harness.controller, MonitorState.monitoring);
        mark('MONITORING_STARTED');

        // The emulator virtual camera scene is continuously moving, so the
        // motion detector must fire within the poll window.
        final motion = await harness.waitForEvent(
          (row) =>
              row.triggerType == 'motion' ||
              row.triggerTypes.contains('motion'),
        );
        expect(motion, isNotNull, reason: 'no motion event on the device');
        expect(motion!.snapshotName, isNotNull,
            reason: 'event has no snapshot reference');

        final files = harness.snapDir
            .listSync()
            .whereType<File>()
            .where((f) => f.path.endsWith('.png') || f.path.endsWith('.jpg'))
            .toList();
        expect(files, isNotEmpty, reason: 'no snapshot PNG written');
        mark('EVENT_RECORDED');

        await tapMonitorButtonAndAwait(
            tester, harness.controller, MonitorState.idle);
      },
    );
  });
}