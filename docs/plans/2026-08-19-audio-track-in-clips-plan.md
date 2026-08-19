# Audio Track in Clips — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an audio track to Android event clips by moving mic capture into the native monitoring service and teeing the single native `AudioRecord`'s PCM to both the YAMNet analysis path (over a native→Dart PCM stream) and a native rolling buffer that is AAC-encoded and muxed into the clip at export.

**Architecture:** Native `MicCapture` (Kotlin, in `camera_service/`) owns the only AudioRecord (16 kHz mono PCM s16le). It pushes PCM to Dart through the `mic_pcm` EventChannel consumed by `NativeMicAudioSource` (an `AudioSource`, feeds the existing `PcmWindowAccumulator`/YAMNet path unchanged) and forwards the same chunks to `VideoClipRecorder.onMicPcm` for the clip buffer. The CameraX recorder stays **video-only**; at export the PCM slice covering the clip window is AAC-LC encoded with `MediaCodec` and muxed alongside the concatenated video with `MediaMuxer` (post-hoc mux).

> **Why not `AudioMixSource` (original Option A):** the CameraX recorder cannot accept an injected PCM/AudioRecord source — `AudioMixSource` does not exist in CameraX 1.3.4 or 1.4.2 (verified against the jars). The recorder only supports `AudioSpec.Source.SOURCE_AUTO`/`SOURCE_CAMCORDER` and opens its own internal AudioRecord. Post-hoc mux works on all API levels and keeps a single AudioRecord (no concurrency risk).

**Tech Stack:** Kotlin, AndroidX CameraX `1.3.4` (`camera-video`), Android `MediaCodec`/`MediaMuxer`/`MediaExtractor`, Flutter method channels (`EventChannel`), `record ^6.2.1` removed from the Android analysis path (kept for other platforms).

**Spec:** `docs/plans/2026-08-19-audio-track-in-clips-design.md`

**Execution rule:** Execute one emulator at a time per `AGENTS.md`; host resources must be free (poll `free -h` / `/proc/loadavg`).

---

### Task 1: Native mic bridge (no recorder changes)

**Files:**
- Create: `security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service/MicCapture.kt`
- Modify: `security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service/MonitoringService.kt`
- Modify: `security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service/CameraServiceChannels.kt`
- Create: `security_cam/lib/sensors/native_mic_audio_source.dart`
- Modify: `security_cam/lib/sensors/audio_source_factory.dart`

- [x] **Step 1: Implement `MicCapture.kt`** — `AudioRecord` (`VOICE_RECOGNITION`, 16 kHz, mono, PCM_16BIT, `minBufferSize` × 2), synchronous `startRecording`, read loop with per-chunk **absolute start sample**.
- [x] **Step 2: Bridge PCM to Dart** — `MonitoringService` starts `MicCapture` when monitoring starts; forwards each chunk to `publishMicPcm`; `CameraServiceChannels` exposes the `mic_pcm` EventChannel.
- [x] **Step 3: Dart `NativeMicAudioSource`** — wraps the event stream into the `Stream<AudioWindow>` contract; `MonitorController`/`PcmWindowAccumulator` unchanged.
- [x] **Step 4: Source factory switch** — `buildAudioSource` returns `NativeMicAudioSource` on Android; other platforms unchanged.
- [x] **Step 5: YAMNet no-crash regression on-device** — suite green on `pixel_24_aosp` (4/4).
- [x] **Step 6: Commit** — `feat: native-owned mic on Android feeding analysis via PCM bridge` (`494ee6a`).

---

### Task 2: Post-hoc PCM mux (audio track on all API levels)

**Files:**
- Modify: `security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service/VideoClipRecorder.kt`
- Modify: `security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service/MicCapture.kt`
- Modify: `security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service/MonitoringService.kt`
- Modify: `security_cam/android/app/src/main/kotlin/io/securitycam/security_cam/camera_service/CameraServiceChannels.kt`
- Modify: `security_cam/lib/storage/video_store.dart` + `test/monitor_controller_test.dart` (`VideoStore.hasAudio`)
- Modify: `security_cam/integration_test/monitoring_on_device_test.dart` (audio assertion + video-bearing motion wait)
- Modify: `security_cam/tool/run_android_integration_tests.sh` (always `EXPECT_CLIP_AUDIO=true`)

- [x] **Step 1: MicCapture callback carries start sample** — `(pcm, startSample)`; `MonitoringService` forwards to `VideoClipRecorder.onMicPcm`.
- [x] **Step 2: Rolling PCM buffer** — `AudioPcmBuffer` (synchronized, ~60 s window, zero-filling `slice`); `micStartWallMicros` on first chunk.
- [x] **Step 3: Clip window alignment** — `segmentStartWallMicros` recorded per segment; audio window = pre-roll segment start − mic start, over total video duration.
- [x] **Step 4: AAC encode + mux** — `encodeAac` (AAC-LC 16 kHz mono @ 48 kbps, non-blocking batch feed/drain, csd-0 captured) and `muxClip` (video concat + audio track, PTS from 0); export offloaded to a dedicated executor so the CameraX executor is never starved.
- [x] **Step 5: hasAudio plumbing** — `videoHasAudio` channel + `VideoStore.hasAudio` (abstract/Noop/Platform) + test double.
- [x] **Step 6: On-device audio-track assertion** — `EXPECT_CLIP_AUDIO` (const) in the full-monitoring test; the test waits for a motion event that carries a clip reference (overlapping batches can drop an export).
- [x] **Step 7: On-device verification** — `pixel_24_aosp` (4/4) and `pixel_34_aosp` (4/4); audio track present (`hasAudio` true), export ~2.5–2.9 s.
- [x] **Step 8: Commit** — `feat: mux native mic audio track into recorded clips` (`ca1308c`).

---

### Task 3: Docs + final verification

- [ ] **Step 1: Update design + plan docs** — this document and the design doc now describe the implemented post-hoc mux (Option A/`AudioMixSource` superseded). Also mark the "Audio track deferred" note (§B9.4) in the main design doc.
- [ ] **Step 2: Full suite + analyze** — `flutter test` (176) and `flutter analyze` clean.
- [ ] **Step 3: Commit** — `docs: audio track in clips (PCM mux) verified on API 24/34`.

---

## Self-Review notes

- **Spec coverage:** native mic (Task 1) ✓; recorder audio via post-hoc PCM mux on all API levels (Task 2) ✓; on-device audio-track assertion (Task 2) ✓; docs (Task 3) ✓.
- **Key risk explicitly managed:** export latency on constrained emulators — offloaded off the CameraX executor; timestamp alignment solved via wall-clock segment starts + real video durations.
- **Not implemented in this phase:** clip-audio toggle, desktop audio (separate workstream — desktop/ffmpeg recording plan).
- **Blast radius:** the native→Dart PCM bridge replaces the `record`-package path on Android only; the `AudioSource` contract is preserved so `MonitorController`/`PcmWindowAccumulator` are unchanged. Export behavior is unchanged for video-only paths; audio is additive.