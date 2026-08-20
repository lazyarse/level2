# Health Watchdog (Local, No Heartbeats) — Design

Date: 2026-08-19
Status: Draft (implementation plan follows)

## Goal

Detect a **silent failure** while monitoring: the camera stops producing analysis
frames, or the audio pipeline stops producing windows, while the app still believes
it is monitoring. Raise it as a first-class `health` trigger event routed through
the normal channel pipeline (one alert per episode), plus a visible in-app error
state, and a recovery event when the stream resumes.

Decision (2026-08-19): **no periodic heartbeat messages** — the watchdog only
alerts when something breaks.

## Current state (verified from code, 2026-08-19)

- **Streams to watch**: `camera.analysisFrames` (broadcast, ~4 fps) and
  `audio.windows` (~1 window/s) are subscribed in `MonitorController.start`
  (`lib/state/monitor_controller.dart:222-227`) through two
  `AnalysisDispatcher`s. The UI preview also subscribes to `analysisFrames`
  directly (`lib/ui/monitor_screen.dart`).
- **Timers pattern**: `_restartPurgeTimer()` (`monitor_controller.dart:207-215`)
  with injectable `purgeInterval`; `AnalysisDispatcher` (W-buffered pipeline) is
  the recently added serialized-input layer.
- **Trigger emission path**: `DetectorPipeline` owns a broadcast
  `StreamController<TriggerEvent>` and emits via `_maybeEmit` (per-detector
  cooldown) (`lib/detection/pipeline.dart:98-109`). `TriggerBatcher` turns
  triggers into batches (snapshot capture), `EventPipeline.handleBatch` delivers +
  records (`lib/event/event_pipeline.dart:44-84`).
- **Controller state**: `MonitorState { idle, starting, monitoring, error }`;
  `error` string surfaces in the monitor screen; start()/stop()/init()/updateSettings()
  lifecycle.

## Design

### 1. `HealthWatchdog` (pure Dart, testable)

New `lib/detection/health_watchdog.dart`:

```dart
class HealthWatchdog {
  HealthWatchdog({
    required void Function(HealthEpisode episode) onEpisode,
    this.stallTimeout = const Duration(seconds: 30),
  });

  void noteFrame(DateTime at);   // called per analysis frame
  void noteAudio(DateTime at);   // called per audio window
  void check(DateTime now);      // called on a periodic tick
  void reset();                  // clears state (stop/start boundary)
}
```

- Tracks the latest frame/window timestamps and a `_stalled` flag per source
  (frame / audio) plus an overall `_episodeActive` flag.
- `check(now)`:
  - if not `_episodeActive` and (`now − lastFrame` > `stallTimeout` **or** `now −
    lastAudio` > `stallTimeout`): set active and fire `onEpisode(stall)` with the
    stalled sources + elapsed.
  - if `_episodeActive` and both sources are fresh again: clear active and fire
    `onEpisode(recovered)`.
- One alert per episode by construction (flags), no cooldown spam.

### 2. Controller wiring

- On `start()`: create the watchdog with `onEpisode` that emits a **health trigger
  through the pipeline** (new `TriggerType.health = 'health'`, e.g.
  `pipeline.emitHealth(StallSources)` — a small public emit method that bypasses
  `_maybeEmit`'s detector cooldown but respects a short health-specific cooldown,
  or a dedicated `HealthDetector`-style path; v1: a `pipeline.emitTrigger(TriggerEvent)`
  guarded by the pipeline's cooldown map under a reserved `health` id).
  Recovery also emits a `health` trigger (detail `'recovered'`), and the controller
  clears its `error`/sets a `healthNote`.
- Add lightweight taps: wrap the existing dispatcher `add` callbacks (or subscribe
  extra listeners) to call `watchdog.noteFrame(timestamp)` /
  `watchdog.noteAudio(timestamp)` — cheap, no backpressure (these are the same
  frames the dispatchers already receive).
- A `Timer.periodic` tick (`healthCheckInterval`, injectable; default 5 s) calls
  `watchdog.check(now)`, armed on `start()` and cancelled on `stop()`/`dispose()`.
- UI: `MonitorScreen` shows a red banner/state when a stall is active (new
  `controller.healthStalled` bool exposed via the existing `ListenableBuilder`);
  recovery clears it.
- Manual start of monitoring with no frames yet (starting state) must not trip the
  watchdog — the watchdog only arms once the first frame/window arrives
  (timestamps start null and `check` is a no-op until both sources have seen data).

### 3. Alert text + routing

- `TriggerType.health`; label "Health"; alert text "Camera feed stalled (audio/
  video)" or "Camera feed recovered". Uses the `detail` plumbing from the tamper
  workstream (`detail: 'stall'` / `'recovered'`).
- Routed like any trigger (respects `DetectorConfig.routeToChannelIds` for the
  health config's default route, e.g. same default as motion). Defaults:
  `DetectorConfig(type: health, enabled: true, cooldown: 5 min, …)` — enabled by
  default so a broken camera always surfaces, but configurable.
- The watchdog's own stall is deliberately decoupled from the `health` detector's
  frame path (a stalled camera can't run frame detectors); the health trigger is
  emitted by the controller, not a detector.

## Verification

- **Unit tests** (`test/health_watchdog_test.dart`): no check until first data;
  stall fires once (no spam across ticks); recovery fires once; per-source
  staleness; `reset()` re-arms.
- **Controller tests** (`test/monitor_controller_test.dart`) with injected
  `healthCheckInterval` + fake clock/stream: frames flowing → no alert; stop the
  frame stream → health trigger via pipeline + `healthStalled` true; resume →
  recovery + cleared state; `stop()` cancels the watchdog.
- **Event text test**: stall/recovered alert text via the `detail` plumbing.
- **Existing suite**: `flutter test` + `flutter analyze` green on Linux desktop.

## Deferred / not in this phase

- Periodic heartbeat messages (explicitly rejected — local-only per decision).
- Alerts for monitoring *paused by schedule* (that's intended, not a fault).
- Device-level checks beyond stream liveness (battery, storage, network reachability).

## Risks

- Low. The watchdog is a thin, pure-Dart wrapper over stream liveness; wiring is
  additive. The main subtlety is not tripping during `starting` (solved by arming
  on first data) and not double-firing with the pipeline's own cooldown (solved by
  a reserved health cooldown + episode flags).