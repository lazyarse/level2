# Buffered, Serialized Analysis Pipeline — Implementation Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [ ]`)
> syntax for tracking. This plan is **deferred** — execute only after explicit go-ahead.

**Goal:** Serialize detector inputs behind small latest-wins buffers so video and
audio analysis never run concurrently, with bounded latency and drop-stale (not
pile-up) overload behavior. Independent of the audio-in-clips workstream.

**Architecture:** One generic `AnalysisDispatcher<T>` (single pending slot,
serialized `process` loop, error-tolerant) per stream. `MonitorController` uses two
instances — one for `AnalysisFrame` → `pipeline.processFrame`, one for
`AudioWindow` → `pipeline.processAudio` — replacing the current
`unawaited(...)` subscriptions. The UI live preview stays on the raw broadcast
stream.

**Spec:** `docs/plans/2026-08-19-buffered-analysis-pipeline-design.md`

**Execution rule:** Do NOT implement now. When executing, prefer Linux desktop
(`flutter test`) for all iteration; the on-device Android suite is a final
regression gate only (the change is Dart-only, not API-specific).

---

### Task 1: `AnalysisDispatcher` + unit tests

**Files:**
- Create: `security_cam/lib/detection/analysis_dispatcher.dart`
- Create: `security_cam/test/analysis_dispatcher_test.dart`

- [ ] **Step 1: Implement `AnalysisDispatcher<T>`**

```dart
class AnalysisDispatcher<T> {
  AnalysisDispatcher({required Future<void> Function(T) process, this.onError});

  // latest-wins: replaces any pending slot
  void add(T input);
  // drains in-flight work and clears the pending slot
  Future<void> dispose();
}
```

Single pending slot; worker loop awaits `process` serially; `process` errors are
caught (try/finally) and routed to `onError` without halting the loop.

- [ ] **Step 2: Unit tests** (`test/analysis_dispatcher_test.dart`)

Processes inputs in order when fast; latest-wins (add A→busy, add B, add C ⇒ C
processed, B dropped); a burst of adds yields max concurrency 1 (track concurrent
`process` entries); a throwing `process` is caught, `onError` fires, and the next
input still processes; `dispose` clears the pending slot and stops the loop.

- [ ] **Step 3: Verify**

```bash
date -R && flutter test test/analysis_dispatcher_test.dart && flutter analyze
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: serialize analysis inputs (latest-wins) so detectors never run concurrently"
```

---

### Task 2: Wire into `MonitorController`

**Files:**
- Modify: `security_cam/lib/state/monitor_controller.dart`

- [ ] **Step 1: Route frames and windows through dispatchers**

Replace the `_frameSub`/`_audioSub` `unawaited(pipeline.process...)` listeners
(`monitor_controller.dart:218-223`) with:

```dart
_frameDispatcher = AnalysisDispatcher<AnalysisFrame>(process: pipeline.processFrame);
_audioDispatcher = AnalysisDispatcher<AudioWindow>(process: pipeline.processAudio);
_frameSub = camera.analysisFrames.listen(_frameDispatcher.add);
_audioSub = audio.windows.listen(_audioDispatcher.add);
```

Add the two dispatchers to `_disposeRuntime` (dispose before the pipeline is
disposed). Do NOT touch `MonitorScreen`'s preview subscription.

- [ ] **Step 2: Verify + commit**

```bash
date -R && flutter test && flutter analyze
git commit -m "refactor: route analysis frames/windows through serialized dispatchers"
```

---

### Task 3: Final verification (on-device regression gate, later stage)

- [ ] **Step 1: On-device suite**

```bash
date -R && ANDROID_HOME=/home/tpa/code/android-env/android-sdk \
  security_cam/tool/run_android_integration_tests.sh pixel_24_aosp
```

Expected: monitoring suite still green (motion + face + person wired paths, native
mic bridge) — serialized processing must not change trigger behavior on the real
device stack. Clean up the emulator afterwards.

- [ ] **Step 2: Commit any fixes, then mark this plan complete**

---

## Self-Review notes

- **Spec coverage:** latest-wins serialized frame path ✓; independent serialized
  audio path ✓; UI preview untouched ✓; error-tolerant worker ✓; unit tests ✓;
  on-device regression gate ✓.
- **Key risk explicitly managed:** slow gated detectors (YOLO) overlapping across
  frames is the motivating bug; latest-wins bounds latency and skips stale frames
  rather than piling up.
- **Not implemented in this phase:** cross-stream prioritization, multi-slot FIFO
  tuning, bundling with the audio-in-clips workstream.
- **Blast radius:** confined to `MonitorController` input wiring + one new class;
  `DetectorPipeline`, detectors, event pipeline, and UI preview are unchanged.