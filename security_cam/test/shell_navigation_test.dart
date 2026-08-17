import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:security_cam/core/models.dart';
import 'package:security_cam/state/monitor_controller.dart';
import 'package:security_cam/storage/event_log.dart';
import 'package:security_cam/storage/event_recorder.dart';
import 'package:security_cam/storage/settings_store.dart';
import 'package:security_cam/storage/snapshot_store.dart';
import 'package:security_cam/ui/app.dart';

class _FakeRecorder implements EventRecorder {
  @override
  Future<void> record(RecordedEvent event) async {}

  @override
  Future<List<String>> deleteEvents({DateTime? olderThan}) async => const [];
}

class _FakeStore implements SnapshotStore {
  @override
  Future<void> delete(String name) async {}

  @override
  Future<Snapshot?> load(String name) async => null;

  @override
  Future<String> save(Snapshot snapshot) async => snapshot.name;
}

void main() {
  testWidgets('Events tab does not recreate its State on navigation',
      (tester) async {
    SharedPreferences.setMockInitialValues({});
    final settingsStore = await SettingsStore.open();
    final controller = MonitorController(
      settingsStore: settingsStore,
      eventRecorder: _FakeRecorder(),
      snapshotStore: _FakeStore(),
    );
    await controller.init();

    var loadCalls = 0;
    await tester.pumpWidget(SecurityCamApp(
      controller: controller,
      eventLoader: () async {
        loadCalls++;
        return const <RecordedEventRow>[];
      },
      snapshotStore: _FakeStore(),
    ));
    await tester.pumpAndSettle();
    expect(loadCalls, 1, reason: 'initState should load once');

    await tester.tap(find.text('Events'));
    await tester.pumpAndSettle();
    expect(loadCalls, 2, reason: 'first visit reloads once (state kept)');

    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    expect(loadCalls, 2, reason: 'navigating away must not reload');

    await tester.tap(find.text('Events'));
    await tester.pumpAndSettle();
    expect(loadCalls, 3, reason: 'returning reloads once (state kept)');

    expect(tester.takeException(), isNull);
  });
}