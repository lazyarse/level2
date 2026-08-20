import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart' as sql;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:security_cam/core/settings.dart';
import 'package:security_cam/sensors/ffmpeg_audio_source.dart';
import 'package:security_cam/sensors/permissions_service.dart';
import 'package:security_cam/state/monitor_controller.dart';
import 'package:security_cam/storage/event_log.dart';
import 'package:security_cam/storage/settings_store.dart';
import 'package:security_cam/storage/snapshot_store.dart';

Future<bool> _ffmpegAvailable() async {
  try {
    final result = await Process.run('ffmpeg', ['-version']);
    return result.exitCode == 0;
  } catch (_) {
    return false;
  }
}

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    sql.databaseFactory = databaseFactoryFfi;
  });

  test('bogus file path surfaces a readable error and tears down', () async {
    if (!await _ffmpegAvailable()) {
      markTestSkipped('ffmpeg not available');
      return;
    }
    SharedPreferences.setMockInitialValues({});
    final settingsStore = await SettingsStore.open();
    final eventLog = await SqliteEventLog.open(inMemoryDatabasePath);
    final snapDir = await Directory.systemTemp.createTemp('scam_snaps');
    final snapshotStore = FileSnapshotStore(snapDir.path);

    final controller = MonitorController(
      settingsStore: settingsStore,
      eventRecorder: eventLog,
      snapshotStore: snapshotStore,
      permissionsService: const NoopPermissionsService(),
    );
    await controller.init();
    await controller.updateSettings(controller.settings.copyWith(
      cameraSource: CameraSource.file,
      cameraSourcePath: '/nonexistent/definitely-missing.mp4',
    ));

    await controller.start();
    expect(controller.state, MonitorState.monitoring);

    await Future<void>.delayed(const Duration(seconds: 3));
    expect(controller.state, MonitorState.error);
    expect(controller.error, contains('ffmpeg'));

    controller.dispose();
    await eventLog.close();
    snapDir.deleteSync(recursive: true);
  });

  test('file source replays a clip and streams real frames', () async {
    if (!await _ffmpegAvailable()) {
      markTestSkipped('ffmpeg not available');
      return;
    }
    final work = await Directory.systemTemp.createTemp('scam_clip');
    final clipPath = '${work.path}/clip.mp4';
    final result = await Process.run('ffmpeg', [
      '-f', 'lavfi',
      '-i', 'testsrc=duration=10:size=160x120:rate=4',
      '-pix_fmt', 'yuv420p',
      '-y',
      clipPath,
    ]);
    if (result.exitCode != 0) {
      markTestSkipped('could not generate test clip: ${result.stderr}');
      return;
    }

    SharedPreferences.setMockInitialValues({});
    final settingsStore = await SettingsStore.open();
    final eventLog = await SqliteEventLog.open(inMemoryDatabasePath);
    final snapDir = await Directory.systemTemp.createTemp('scam_snaps');
    final snapshotStore = FileSnapshotStore(snapDir.path);

    final controller = MonitorController(
      settingsStore: settingsStore,
      eventRecorder: eventLog,
      snapshotStore: snapshotStore,
      permissionsService: const NoopPermissionsService(),
    );
    await controller.init();
    await controller.updateSettings(controller.settings.copyWith(
      cameraSource: CameraSource.file,
      cameraSourcePath: clipPath,
    ));

    var frames = 0;
    late final StreamSubscription<dynamic> sub;
    await controller.start();
    sub = controller.analysisFrames!.listen((_) => frames++);
    expect(controller.state, MonitorState.monitoring);

    await Future<void>.delayed(const Duration(seconds: 4));
    await sub.cancel();
    expect(controller.state, MonitorState.monitoring,
        reason: 'error=${controller.error}');
    expect(frames, greaterThan(0));

    controller.dispose();
    await eventLog.close();
    snapDir.deleteSync(recursive: true);
    work.deleteSync(recursive: true);
  });

  test('audio file source replays a tone and emits audio windows', () async {
    if (!await _ffmpegAvailable()) {
      markTestSkipped('ffmpeg not available');
      return;
    }
    final work = await Directory.systemTemp.createTemp('scam_audio');
    final audioPath = '${work.path}/tone.wav';
    final result = await Process.run('ffmpeg', [
      '-f', 'lavfi',
      '-i', 'sine=frequency=440:duration=10:sample_rate=16000',
      '-y',
      audioPath,
    ]);
    if (result.exitCode != 0) {
      markTestSkipped('could not generate tone: ${result.stderr}');
      return;
    }

    final source = FfmpegAudioSource('file', audioPath);
    var windows = 0;
    var sampleRate = 0;
    var windowSamples = 0;
    final sub = source.windows.listen((w) {
      windows++;
      sampleRate = w.sampleRate;
      windowSamples = w.samples.length;
    });
    final failureSub = source.failures.listen((_) {});
    source.start();

    await Future<void>.delayed(const Duration(seconds: 3));
    await sub.cancel();
    await failureSub.cancel();
    await source.dispose();

    expect(windows, greaterThan(0), reason: 'no windows arrived from tone');
    expect(sampleRate, 16000);
    expect(windowSamples, greaterThan(0));
    work.deleteSync(recursive: true);
  });
}