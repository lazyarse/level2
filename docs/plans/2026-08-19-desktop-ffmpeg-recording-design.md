# Desktop / ffmpeg Video Recording — Design

Date: 2026-08-19
Status: Draft (implementation plan follows; NOT yet scheduled for implementation)

## Goal

Give the **Linux desktop** app parity with Android event clips: on a trigger, record a
pre-roll + post-roll clip and link it from the Events tab. Today `VideoStore` is a
`NoopVideoStore` on desktop (design doc: *"desktop/ffmpeg video recording (Android-only
this pass)"*).

## Current state

- `MonitorController.start()` wires `videoStore` (Android = `PlatformVideoStore` →
  native ring-buffer + MediaStore; desktop = `NoopVideoStore` → nothing).
- On trigger, `TriggerBatcher.captureVideo(triggerAt)` → `videoStore.exportClip(...)`
  with `preRollSeconds`/`postRollSeconds` from settings. `EventsScreen` shows a video
  button only when the event row has `videoName != null`; `openVideo` → `videoStore.open`.
- Desktop camera: `SimulatedCameraSession` (synthetic frames) or `FfmpegCameraSession`
  (webcam/file via `ffmpeg` rawvideo pipes, 160×120 gray analysis stream). `ffmpeg` 8.x
  is present on the dev host (`/usr/bin/ffmpeg`).
- The app already builds ffmpeg argv in `lib/sensors/ffmpeg_camera_session.dart` /
  `ffmpeg_audio_source.dart` with tested argv builders (`test/ffmpeg_args_test.dart`,
  `ffmpeg_audio_args_test.dart`).

## Design

### `FfmpegVideoStore implements VideoStore` (Linux only)

Chosen by `MonitorController` when `!Platform.isAndroid && settings.recordVideo`:

- Stores clips in the app documents dir under `clips/` (uses `path_provider`
  `getApplicationDocumentsDirectory`), filename via the existing
  `videoFileName(timestamp, cameraName)` helper.
- `exportClip`: the recorder below has been **continuously recording while monitoring**
  (single ffmpeg process, ring-buffer semantics via an always-on capture). On a trigger
  it keeps writing for `postRollSeconds` then stops the process; the resulting file is
  moved to the final name and returned. Pre-roll is automatic because capture never
  stopped. (This mirrors the native "always recording, export the tail" model without a
  real ring buffer.)
- `delete`/`exists`/`videoInfo`: file ops (`videoInfo` reads width/height from the mp4
  header via a tiny ffprobe/`dart:io` parse — ffprobe is on the dev host, but parsing the
  `ftyp`/`tkhd` boxes in Dart is dependency-free and testable; **prefer a Dart parse**).
- `open`: `xdg-open <path>` via `Process.run` (desktop parity for the Events-tab button).

### `DesktopClipRecorder`

- Owns the capture ffmpeg process while monitoring. Inputs:
  - **Video**: raw RGB frames piped from a new `CameraSession.recordFrames` stream
    (`Stream<AnalysisFrame>?`, color frames at record resolution).
    - `SimulatedCameraSession`: `recordFrames` = `analysisFrames` (already synthetic color).
    - `FfmpegCameraSession`: one ffmpeg process emits **two** rawvideo pipes — the
      existing 160×120 gray analysis output plus a new 320×240 rgb24 record output
      (single process, so a webcam is opened once — avoids v4l2 EBUSY on double-open):
      `ffmpeg -i <src> -filter_complex "[0:v]split[a][b];[a]scale=160:120,format=gray[a1];[b]scale=320:240,format=rgb24[b1]" -map [a1] -f rawvideo pipe:1 -map [b1] -f rawvideo pipe:2`.
    - Default `recordFrames` = `analysisFrames` (null-safe fallback for other sessions).
  - **Audio** (optional, milestone 2): pipe the desktop mic `AudioWindow` PCM (s16le
    16 kHz) as a second ffmpeg input (`-f s16le -ar 16000 -ac 1 -i pipe:3`), muxed with
    `-c:a aac`. Skipped when the source is simulated/silent.
- ffmpeg args are built by a pure `buildRecordingArgs(...)` helper (unit-testable,
  mirrors `ffmpeg_args_test.dart` style): `-f rawvideo -pix_fmt rgb24 -s 320x240 -r 10 -i pipe:0 [-f s16le ... -i pipe:3] -c:v libx264 -preset veryfast -pix_fmt yuv420p -movflags +faststart <out>`.
- Lifecycle: start on monitoring start (when `recordVideo`), stop+finalize on
  `exportClip` (post-roll delay) or on monitoring stop (discard unless exporting).

### Wiring

- `MonitorController.start()`: if `!Platform.isAndroid && settings.recordVideo` →
  `videoStore = FfmpegVideoStore()` and create the recorder, subscribing to
  `camera.recordFrames ?? camera.analysisFrames` and (milestone 2) `audio.windows`.
- `captureVideo(triggerAt)` stays untouched (recorder answers `exportClip`).

## Testing

- Unit: `buildRecordingArgs` exact-argv tests; `FfmpegVideoStore` file lifecycle
  (`exportClip`/`exists`/`delete`/`videoInfo` via a fake recorder); `CameraSession`
  `recordFrames` default fallback.
- Live (Linux, gated on ffmpeg presence like `ffmpeg_live_test.dart`): record a real
  clip from `testsrc` file source → file exists, `videoInfo` dims match, `open` invoked,
  `delete` removes it. Events-tab smoke: trigger → row shows the video button → opens.
- Desktop `flutter test -d linux` integration mirroring `monitoring_on_device_test.dart`
  is out of scope (that suite is Android-only).

## Known risks / decisions

- **Continuous single-file capture** means long monitoring → large temp file; mitigated
  by segmenting (ffmpeg `-f segment`) only if needed. MVP accepts it (dev platform).
- **Webcam double-open** avoided via single-process dual-output tee (see above).
- **Record resolution fixed at 320×240 / 10 fps** this pass (configurable later via the
  existing `videoQuality` semantics or a constant).
- Clips on desktop are a **dev** parity feature; no MediaStore (that's Android-only).

## Deferred

- Configurable record resolution/fps on desktop.
- Desktop clip audio (milestone 2 in the plan).
- Real ring buffer / segment rotation on desktop (only if long sessions matter).
