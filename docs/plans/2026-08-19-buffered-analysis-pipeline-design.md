# Buffered, Serialized Analysis Pipeline — Design

Date: 2026-08-19
Status: Draft (implementation plan follows; NOT yet scheduled for implementation)

## Goal

Make the detector analysis path deterministic and load-bounded by feeding video
and audio into small buffers consumed by a **serialized worker**: detectors never
run concurrently, processing is slightly delayed but bounded, and stale inputs are
dropped (latest-wins) instead of piling up. This is a standalone robustness item,
deferred from the audio-in-clips workstream (decision 2026-08-19) — it is **not**
required for Path 2 (post-hoc audio mux) mechanics.

## Current state (verified from code, 2026-08-19)

- **Intra-frame: detectors are already sequential.** `DetectorPipeline.processFrame`
  (`lib/detection/pipeline.dart:64`) runs motion synchronously, then awaits the
  motion-gated detectors (face, person) one after another. `processAudio`
  (`pipeline.dart:82`) classifies once via YAMNet, then runs audio detectors
  sequentially. No concurrent splitting *within* a frame.
- **Inter-frame: processing overlaps.** `MonitorController` subscribes with
  `unawaited(pipeline.processFrame(frame))` and `unawaited(pipeline.processAudio(window))`
  (`lib/state/monitor_controller.dart:218-223`). When an async gated detector
  (YOLO26n person, face) takes longer than the 250 ms frame cadence, consecutive
  frames' `processFrame` calls run concurrently. There is **no backpressure** at
  the Dart layer — under load (slow phone, swiftshader emulator) frames pile up in
  the event loop, CPU saturates, and detection latency jitters.
- **Native already drops-to-latest**: CameraX `ImageAnalysis` uses
  `STRATEGY_KEEP_ONLY_LATEST` plus a 250 ms gate in `MonitoringService.kt`. The
  Dart re-broadcast (`AndroidCameraSession.analysisFrames`, broadcast controller)
  is the layer without backpressure.
- **Two consumers of frames**: `MonitorController` (detection) and `MonitorScreen`
  (live preview, `lib/ui/monitor_screen.dart:41`) both subscribe to the broadcast
  `analysisFrames`. The preview must stay on the live stream.
- **Audio cadence is low** (~1 window every 0.975 s, 15600 samples); YAMNet
  classify is fast, so audio rarely overlaps today — but it is not serialized.

## Design

### 1. `AnalysisDispatcher<T>` — generic latest-wins serialized worker

New class `lib/detection/analysis_dispatcher.dart`:

```dart
class AnalysisDispatcher<T> {
  AnalysisDispatcher({
    required Future<void> Function(T input) process,
    this.onError,
  });

  void add(T input);      // latest-wins: replaces any pending slot
  Future<void> dispose(); // clears pending slot, drains in-flight work
}
```

Semantics:

- A single **pending slot** per instance. If `add` is called while the worker is
  processing, the slot is overwritten (the older pending input is dropped).
- The worker loop takes the slot when free and `await`s `process(input)`
  **serially** — at most one `process` call in flight per dispatcher.
- `process` errors are caught (`try/finally` so the loop always continues), routed
  to `onError`, and never kill the dispatcher.
- Deliberately *not* a queue: dropping stale inputs is the desired overload
  behavior (fresh state wins; matches the native drop-to-latest philosophy).

### 2. Two dispatchers, not one

Create **two** instances in `MonitorController` (one for frames, one for audio)
so a slow video pass (YOLO person) never delays an audio window:

- `AnalysisDispatcher<AnalysisFrame>(process: pipeline.processFrame)`
- `AnalysisDispatcher<AudioWindow>(process: pipeline.processAudio)`

Wiring replaces the `unawaited(...)` subscriptions at `monitor_controller.dart:218-223`:

```dart
_frameDispatcher = AnalysisDispatcher<AnalysisFrame>(process: pipeline.processFrame);
_audioDispatcher = AnalysisDispatcher<AudioWindow>(process: pipeline.processAudio);
_frameSub = camera.analysisFrames.listen(_frameDispatcher.add);
_audioSub = audio.windows.listen(_audioDispatcher.add);
```

- The **UI preview is untouched**: it subscribes to `camera.analysisFrames`
  directly and keeps the live (unbuffered) stream.
- `audio.start()` order is unchanged.

### 3. Latency and correctness

- Added latency is bounded by one `process` duration per stream (typically
  ≪250 ms for video when motion-gated detectors are idle; up to one gated-detector
  run when motion is firing; ≪1 s for audio). Well inside the pre-roll and
  trigger-batch tolerance.
- Cooldown stays correct: `AnalysisFrame.timestamp` is set at frame arrival
  (`android_camera_session.dart:139`), not at processing time.
- Under overload, frames (or windows) are skipped — acceptable and strictly
  better than pile-up/overlap.

## Verification

- **Unit tests** (`test/analysis_dispatcher_test.dart`): in-order processing when
  fast; latest-wins (add A→process, add B, add C while busy ⇒ C processed, B
  dropped); never-overlaps (a burst of adds yields max concurrency 1); `process`
  errors are caught and do not halt the loop; `dispose` drains/cancels cleanly.
- **Existing suite**: `flutter test` (176 tests) + `flutter analyze` green on
  Linux desktop — no behavior change for fast paths.
- **On-device regression** (final gate, later stage): the Android monitoring
  suite on `pixel_24_aosp` (and `pixel_34_aosp` only if the task is API-34
  specific — it is not), per `AGENTS.md` conventions.

## Deferred / not in this phase

- Cross-stream prioritization (audio-vs-video priority scheduling).
- Shared buffer across streams (explicitly rejected: audio must not queue behind
  video).
- Tuning the slot size (single-slot latest-wins chosen; a 2-3 slot FIFO for
  frames could reduce skipping at the cost of latency — not needed now).
- Bundling with the audio-in-clips workstream (kept independent).

## Risks

- Low. The change is confined to the detection-input wiring; the pipeline,
  detectors, UI preview, and event side are untouched. Backpressure is strictly
  better than the current pile-up behavior. Main risk is a regression in the
  fast-path ordering, covered by the unit tests.