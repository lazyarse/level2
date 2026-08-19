# Audio Track in Clips — Design

Date: 2026-08-19
Status: Implemented (AT1 native mic bridge + AT2 PCM mux, verified on-device API 24/34)

## Goal

Add an audio track to Android event video clips. Today clips were **video-only**
(`VideoClipRecorder.kt` writes a CameraX `Recorder` without an audio `MediaSpec`).
This is the "audio track in clips" deferred item from the main design doc
(§B9.4: *"Audio track deferred to avoid concurrent-mic risk with the analysis
path (video-only this pass)"*).

## Context & the constraint that shaped the design

- **Analysis mic**: the native-owned `MicCapture` (16 kHz mono PCM, `AudioRecord`
  in the monitoring FGS) feeds the Dart YAMNet analysis path via the
  `mic_pcm` `EventChannel` (`NativeMicAudioSource`). One AudioRecord — no
  concurrent-mic risk.
- **Recorder mic**: CameraX `VideoCapture<Recorder>` (`VideoClipRecorder.kt`,
  CameraX `1.3.4` pinned for the `bindToLifecycle` Kotlin collision).
- **Verified fact**: CameraX offers **no way to inject a custom PCM source into
  the recorder's audio pipeline**. The original plan proposed
  `AudioMixSource`/`AudioSpec.setSource`, but that API **does not exist** in
  CameraX 1.3.4 or 1.4.2 (inspected the actual jars). `Recorder.Builder` only
  supports `AudioSpec.Source.SOURCE_AUTO`/`SOURCE_CAMCORDER`, and CameraX opens
  its own internal `AudioRecord` — there is no `setMediaSpec`-style injection.

**Consequence:** the recorder must stay **video-only** and the audio track is
added by **post-hoc muxing** at export time: the mic PCM is teed into a native
rolling buffer while recording, sliced to the clip window, AAC-encoded with
`MediaCodec`, and muxed alongside the concatenated video with `MediaMuxer`.
This works on **all API levels** (no API 31+ gate).

## Design

### 1. Mic PCM tee (`onMicPcm`)

`MicCapture` (owns the only AudioRecord, 16 kHz mono s16le, `VOICE_RECOGNITION`)
delivers each PCM chunk with its **absolute start sample** on the mic timeline.
`MonitoringService` forwards each chunk to:
1. `CameraServiceChannels.publishMicPcm(pcm)` — the Dart analysis stream; and
2. `VideoClipRecorder.onMicPcm(pcm, startSample)` — the clip audio buffer.

On the first chunk, `VideoClipRecorder` records `micStartWallMicros`
(`SystemClock.elapsedRealtimeNanos`): the wall-clock origin of the mic timeline.

### 2. Rolling PCM buffer (`AudioPcmBuffer`)

A synchronized, bounded buffer of mono s16le chunks keyed by absolute start
sample. A rolling window of the last 60 s bounds memory (~1.9 MB at 16 kHz).
`slice(startSample, endSample)` returns a contiguous window, **zero-filling**
regions where no mic data exists (before the first chunk, after the last, or
gaps) — so alignment never throws and short windows still produce silence.

### 3. Clip window alignment

Each recorded segment records its **wall-clock start** when the recording is
started (`segmentStartWallMicros[file.path]`). At export the audio window is:

- `audioStartMicros = preRollSegment.startWallMicros - micStartWallMicros`
  (relative to the mic timeline; negative → leading silence is prepended).
- `audioEndMicros = audioStartMicros + totalVideoDurationUs`, where the video
  duration is the sum of each input's last sample time (real durations, so the
  audio never drifts across concatenated segments).

### 4. AAC encode + mux (`muxClip`)

`completeExport` runs the heavy work on a **dedicated export executor** so the
CameraX executor (ring loop + Finalize events) is never starved. It:

1. Computes the total video duration (one metadata pass).
2. Slices the PCM window, pads the final AAC frame with zeros, and encodes
   **AAC-LC 16 kHz mono @ 48 kbps** via `MediaCodec` (non-blocking batch
   feed/drain; csd-0 captured from the codec-config buffer).
3. Creates the `MediaMuxer`, adds the video track (first input) and the audio
   track (with csd-0), then writes the concatenated video samples (existing
   timestamp-offset logic) followed by the audio frames (PTS from 0).

**Fallback:** if no PCM ever arrived or the AAC encode fails, the clip is
muxed video-only — an export is never lost for lack of audio.

### 5. Lifecycle

- `RECORD_AUDIO` already granted for analysis; no new permission.
- Mic starts before `VideoCapture.bindToLifecycle` (no recorder dependency now —
  the audio path is fully decoupled from CameraX).
- `onMonitoringStopped` clears the PCM buffer / segment map only when not
  exporting (a stopped export's Finalize still completes).

## UX / settings

No new toggle: audio is **always included** when the mic is running. (A future
`clipAudioEnabled` privacy setting is noted, not planned.)

## Verification (on-device, API 24 + API 34)

- Export a motion clip → assert an audio track is present
  (`MediaExtractor` track MIME starts `audio/`), clip plays, dimensions
  unchanged, `videoInfo` returns dims, delete works.
- `EXPECT_CLIP_AUDIO` is always `true` now (audio on all API levels); the host
  runner can still assert video-only via `--dart-define`.
- YAMNet regression: native mic analysis stream still fires (no-crash gate).
- Full monitoring suite + screen-off gate green on both images.
- Verified on `pixel_24_aosp` (4/4) and `pixel_34_aosp` (4/4); API 34 stores straight to MediaStore
  `Movies/SecurityCam` (no app-private fallback), export ~2.6 s.

## Risks

- **AAC encode cost** on constrained emulators: mitigated by offloading export
  off the CameraX executor and using a non-blocking batch feed/drain (measured
  ~2.9 s for a ~10 s clip on `pixel_24_aosp` under load).
- **Timestamp alignment** across concatenated segments: solved by deriving the
  audio window from wall-clock segment starts + real video durations, so the
  audio track is one contiguous slice aligned to video PTS 0.
- **Emulator MediaStore write** (API 24, no `WRITE_EXTERNAL_STORAGE`): clips fall
  back to the app-private `filesDir/videos` store (pre-existing path); `exists`/
  `hasAudio`/`videoInfo` check both stores.

## Deferred / not in this phase

- Configurable clip-audio toggle.
- Audio for desktop (see the desktop/ffmpeg recording design doc).