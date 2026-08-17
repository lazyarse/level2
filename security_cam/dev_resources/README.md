# Dev resources (prototyping only)

`clip2.mp4` — a 30 s looping clip of the classic BBC Test Card F (1980s) at 720×576 / 25 fps
with a running HH:MM:SS clock and frame counter. Built from the tvark.org scan of the broadcast
card; used to drive the detection pipeline with a real recorded signal during development.

## How to use

- **Video**: Settings → Sources → **Camera source = Video file** → path =
  `<repo>/security_cam/dev_resources/clip2.mp4` → Save → Start. The pipeline scales it to 160×120.
  Note: the still card produces no motion signal; it's for exercising the file-source/looping path
  (the clock shows playback is advancing).
- **Audio**: the same clip's soundtrack (Test Card F lofi music) works as an audio source too:
  **Audio source = Audio file** → same path → Save → Start. `ffmpeg` re-samples to 16 kHz mono
  s16le. Note: the mock classifier rarely fires on the music — it's for exercising the
  audio-file/looping path, not for producing events.

## Delete me once out of prototyping

This is **dev-time only** — the mobile `camera_service` module / iOS plugin ignore the desktop
source switches and never read this. When prototyping is done:

1. Delete this file (and this folder).
2. Remove `cameraSource` / `cameraSourcePath` / `audioSource` / `audioSourcePath` from `AppSettings`
   and the Sources section in the Settings screen.
3. Delete `lib/sensors/ffmpeg_camera_session.dart`, `lib/sensors/ffmpeg_audio_source.dart`,
   `lib/sensors/camera_source_factory.dart`, `lib/sensors/audio_source_factory.dart`,
   `lib/sensors/gray_frame_assembler.dart`, `lib/sensors/pcm_window_accumulator.dart` and their
   tests; revert `MonitorController.start()` to the sim sessions.

See **Appendix D** in `docs/plans/2026-08-17-security-cam-app-design.md` for the full removal
path. No pub dependency is involved.
