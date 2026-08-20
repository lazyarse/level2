# Desktop / ffmpeg Video Recording — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Linux desktop app event video clips (pre-roll + post-roll) so the Events-tab video button works on desktop, using `ffmpeg` (dev-host tool) with a continuous-capture recorder.

**Architecture:** A `DesktopClipRecorder` runs one ffmpeg process while monitoring, fed raw RGB frames from `CameraSession.recordFrames` (a new optional stream; simulated = analysis frames, ffmpeg webcam/file = a second rawvideo output of the same single ffmpeg process) and (milestone 2) desktop mic PCM as a second input. `FfmpegVideoStore implements VideoStore` wraps it: `exportClip` finalizes the post-roll tail to a named mp4 in the documents dir; `delete`/`exists`/`videoInfo` are file ops (Dart header parse); `open` = `xdg-open`. `MonitorController` selects it on non-Android when `recordVideo`.

**Tech Stack:** Flutter/Dart, `dart:io` (`Process`, files), `path_provider` (already a dep), `ffmpeg` CLI (dev host), existing `videoFileName`/`mediaFileName` helpers.

**Spec:** `docs/plans/2026-08-19-desktop-ffmpeg-recording-design.md`

**Execution rule:** NOT scheduled yet — execute only after explicit go-ahead. Linux-only work (no emulator).

---

### Task 1: `recordFrames` on `CameraSession` + dual-output `FfmpegCameraSession`

**Files:**
- Modify: `security_cam/lib/core/camera_session.dart`
- Modify: `security_cam/lib/sensors/simulated_camera_session.dart`
- Modify: `security_cam/lib/sensors/ffmpeg_camera_session.dart`
- Test: `security_cam/test/camera_source_factory_test.dart` (Modify)

- [ ] **Step 1: Add the optional stream**

In `camera_session.dart`:
```dart
/// Color frames for desktop clip recording, at record resolution. Null when the
/// session has no separate record stream (fall back to [analysisFrames]).
Stream<AnalysisFrame>? get recordFrames => null;
```

- [ ] **Step 2: Simulated session returns its analysis stream**

`SimulatedCameraSession`:
```dart
@override
Stream<AnalysisFrame>? get recordFrames => analysisFrames;
```

- [ ] **Step 3: `FfmpegCameraSession` dual output**

Build a second rawvideo output (320×240 rgb24) in the same ffmpeg process, e.g. via
`-filter_complex "[0:v]split[a][b];[a]scale=160:120,format=gray[a1];[b]scale=320:240,format=rgb24[b1]"` with `-map [a1] ... pipe:1` (analysis, unchanged) and `-map [b1] -f rawvideo -pix_fmt rgb24 -s 320x240 -r 10 pipe:2` (record). Expose the pipe-2 stream as `recordFrames` (decode each rawvideo frame into a `ColorBitmap` `AnalysisFrame`).

Update/extend the exact-argv tests in `ffmpeg_args_test.dart` / `camera_source_factory_test.dart` to assert the new dual-output argv (and that unsupported sources still single-output).

- [ ] **Step 4: Verify**

Run: `date -R && flutter test test/ffmpeg_args_test.dart test/camera_source_factory_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: CameraSession.recordFrames + dual-output ffmpeg record pipe"
```

---

### Task 2: `buildRecordingArgs` + `DesktopClipRecorder`

**Files:**
- Create: `security_cam/lib/recording/build_recording_args.dart`
- Create: `security_cam/lib/recording/desktop_clip_recorder.dart`
- Test: `security_cam/test/ffmpeg_recording_args_test.dart` (Create)

- [ ] **Step 1: Write the failing tests**

`test/ffmpeg_recording_args_test.dart` (mirror `ffmpeg_args_test.dart`):
- video-only: exact argv contains `-f rawvideo -pix_fmt rgb24 -s 320x240 -r 10 -i pipe:0`, `-c:v libx264 -preset veryfast -pix_fmt yuv420p -movflags +faststart`, and the output path last.
- with audio (milestone 2): argv contains `-f s16le -ar 16000 -ac 1 -i pipe:1` and `-c:a aac -ac 1`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `date -R && flutter test test/ffmpeg_recording_args_test.dart`
Expected: FAIL — helpers not defined.

- [ ] **Step 3: Implement**

`build_recording_args.dart`:
```dart
List<String> buildRecordingArgs({
  required int width, required int height, required int fps,
  required String outputPath, bool withAudio = false,
}) => [
  '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', '${width}x$height',
  '-r', '$fps', '-i', 'pipe:0',
  if (withAudio) ...['-f', 's16le', '-ar', '16000', '-ac', '1', '-i', 'pipe:1'],
  '-c:v', 'libx264', '-preset', 'veryfast', '-pix_fmt', 'yuv420p',
  '-movflags', '+faststart',
  if (withAudio) ...['-c:a', 'aac', '-ac', '1'],
  outputPath,
];
```

`desktop_clip_recorder.dart`:
- `start({required Stream<AnalysisFrame> recordFrames, Stream<AudioWindow>? audioWindows, required String workingFile, int width = 320, int height = 240, int fps = 10})` — spawns `Process.start('ffmpeg', args)`; writes rgb24 bytes from `recordFrames.bgr` to `stdin`; (milestone 2) writes PCM bytes from `audioWindows` to `stdin` of the second pipe — **note**: with two pipe inputs ffmpeg needs `-i pipe:0` and `-i pipe:1`; a single stdin can't carry both, so milestone 2 uses `pipe:` for audio only when implemented (see plan Task 5).
- `Future<String?> finish({required Duration postRoll})` — waits `postRoll`, closes stdin, waits exit, returns the working file.
- `Future<void> abort()` — kill process, delete working file.
- Tracks ffmpeg stderr for readable errors (expose a `failures` stream like `FfmpegCameraSession`).

- [ ] **Step 4: Verify**

Run: `date -R && flutter test test/ffmpeg_recording_args_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: DesktopClipRecorder + recording argv builder"
```

---

### Task 3: `FfmpegVideoStore` (file lifecycle + header parse)

**Files:**
- Create: `security_cam/lib/storage/ffmpeg_video_store.dart`
- Create: `security_cam/lib/storage/mp4_dimensions.dart`
- Test: `security_cam/test/ffmpeg_video_store_test.dart` (Create)
- Test: `security_cam/test/mp4_dimensions_test.dart` (Create)

- [ ] **Step 1: Write the failing tests**

`test/mp4_dimensions_test.dart`: a minimal synthetic MP4-like byte buffer with a `tkhd` box parses width/height; empty/garbage returns null.
`test/ffmpeg_video_store_test.dart` (fake recorder injected): `exportClip` calls recorder `finish` with the settings' post-roll and returns the final display name; `exists` true/false; `delete` removes the file; `videoInfo` returns parsed dims.

- [ ] **Step 2: Run tests to verify they fail**

Run: `date -R && flutter test test/ffmpeg_video_store_test.dart test/mp4_dimensions_test.dart`
Expected: FAIL.

- [ ] **Step 3: Implement**

`mp4_dimensions.dart`: walk the top-level `moov` → `trak` → `tkhd` boxes; read `width`/`height` from the `tkhd` (fixed-16.16 at the version-1/2 offset); return `VideoClipInfo` or null.

`ffmpeg_video_store.dart`:
```dart
class FfmpegVideoStore implements VideoStore {
  final Directory clipsDir;
  DesktopClipRecorder? _recorder;

  Future<void> startRecording({required Stream<AnalysisFrame> frames,
      required int width, required int height, required int fps}) async { ... }

  @override
  Future<String?> exportClip({required DateTime triggerAt,
      required String cameraName, required int preRollSeconds,
      required int postRollSeconds}) async {
    final tmp = _recorder.finish(postRoll: Duration(seconds: postRollSeconds));
    final name = videoFileName(timestamp: triggerAt, cameraName: cameraName);
    await File(tmp).rename('${clipsDir.path}/$name');
    return name;
  }
  // delete/exists/open (xdg-open)/videoInfo (mp4_dimensions) ...
}
```
Clips dir = `<documents>/clips` via `path_provider`.

- [ ] **Step 4: Verify**

Run: `date -R && flutter test test/ffmpeg_video_store_test.dart test/mp4_dimensions_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: FfmpegVideoStore with clip lifecycle + mp4 dimension parse"
```

---

### Task 4: Wire into `MonitorController`

**Files:**
- Modify: `security_cam/lib/state/monitor_controller.dart`
- Test: `security_cam/test/monitor_controller_test.dart` (Modify)

- [ ] **Step 1: Write the failing test**

In `monitor_controller_test.dart`: on a desktop-like platform (`Platform.isLinux`) with `recordVideo: true`, starting monitoring creates an `FfmpegVideoStore` and the recorder subscribes to `recordFrames`; a trigger's `exportClip` produces a video name. (Inject a fake clock/frames stream; assert the recorder was started and `exportClip` invoked.)

- [ ] **Step 2: Run test to verify it fails**

Run: `date -R && flutter test test/monitor_controller_test.dart`
Expected: FAIL — controller still uses `NoopVideoStore` on Linux.

- [ ] **Step 3: Implement**

In `MonitorController.start()`:
```dart
if (!Platform.isAndroid && settings.recordVideo) {
  final store = FfmpegVideoStore();
  _desktopStore = store;
  videoStore = store;
  await store.startRecording(
    frames: camera.recordFrames ?? camera.analysisFrames,
    width: 320, height: 240, fps: 10);
}
```
`_disposeRuntime` aborts the recorder when stopping without an export. Keep the injected `videoStore` override for tests (existing constructor param wins).

- [ ] **Step 4: Verify**

Run: `date -R && flutter test test/monitor_controller_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: wire DesktopClipRecorder into MonitorController on Linux"
```

---

### Task 5 (milestone 2): desktop clip audio

**Files:**
- Modify: `security_cam/lib/recording/desktop_clip_recorder.dart`
- Modify: `security_cam/lib/state/monitor_controller.dart`

- [ ] **Step 1: Second audio input**

The recorder's ffmpeg gets `-i pipe:1` (s16le PCM) and the mic `AudioWindow`s are written to a second `stdin` (open `stdin2` on the `Process`). Guard: only when the audio source is a real mic (not simulated), so the clip has a track only when sound was actually captured.

- [ ] **Step 2: Verify live**

`test/ffmpeg_live_test.dart`-style live test: record a clip from `testsrc` + a generated PCM tone; assert the mp4 contains an audio track (parse with `mp4_dimensions`-adjacent helper or `ffprobe` when present).

- [ ] **Step 3: Commit**

```bash
git commit -m "feat: mux desktop mic audio into event clips"
```

---

### Task 6: Docs + final verification

- [ ] **Step 1: Update main design doc**

Mark the "desktop/ffmpeg video recording (Android-only this pass)" deferred note as implemented for Linux.

- [ ] **Step 2: Full suite + analyze + desktop live smoke**

Run: `date -R && flutter test && flutter analyze` then a manual `flutter run -d linux` smoke: trigger on the simulated scene → Events row shows the video button → `open` launches the player.

- [ ] **Step 3: Commit**

```bash
git add docs security_cam
git commit -m "docs: desktop ffmpeg video recording implemented"
```

---

## Self-Review notes

- **Spec coverage:** record stream (Task 1) ✓; recorder + argv (Task 2) ✓; store lifecycle + header parse (Task 3) ✓; controller wiring (Task 4) ✓; audio milestone (Task 5) ✓; docs (Task 6) ✓.
- **Known risk called out:** webcam double-open avoided by single-process dual output (Task 1); long-session temp file growth accepted for MVP.
- **Not in scope:** MediaStore on desktop, configurable desktop resolution/fps, real ring-buffer rotation.
