# Health Watchdog (Local, No Heartbeats) — Implementation Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [ ]`)
> syntax for tracking. This plan is part of the multi-workstream feature batch
> (2026-08-19); execute after go-ahead.

**Goal:** A local watchdog that alerts (once per episode) when the camera feed or
audio stream stalls while monitoring, routed as a `health` trigger event, with a
visible in-app state and a recovery event.

**Architecture:** Pure-Dart `HealthWatchdog` tracks frame/window timestamps; the
controller ticks it on an injectable interval and emits `health` triggers through
the pipeline (which the stalled camera cannot do itself). `detail` ('stall' /
'recovered') shapes the alert text via the tamper-workstream plumbing.

**Spec:** `docs/plans/2026-08-19-health-watchdog-design.md`

**Execution rule:** Prefer Linux desktop (`flutter test`) for all iteration; pure Dart.

---

### Task 1: `HealthWatchdog` + trigger plumbing

**Files:**
- Create: `security_cam/lib/detection/health_watchdog.dart`
- Create: `security_cam/test/health_watchdog_test.dart`
- Modify: `security_cam/lib/core/models.dart`
- Modify: `security_cam/lib/detection/pipeline.dart`
- Modify: `security_cam/lib/event/event_pipeline.dart`
- Modify: `security_cam/lib/ui/settings_screen.dart`
- Modify: `security_cam/lib/ui/events_screen.dart`

- [ ] **Step 1:** `HealthWatchdog` (design §1) with `noteFrame/noteAudio/check/reset`,
  stall/recovery episode flags, `onEpisode(HealthEpisode)` callback.
- [ ] **Step 2:** `TriggerType.health = 'health'`; `health` label + `_iconFor`
  entry via the shared label map; `_alertText` handles `detail: 'stall'` →
  "Camera feed stalled (…)" and `'recovered'` → "Camera feed recovered" (reuses
  the single-trigger `detail` path).
- [ ] **Step 3:** `pipeline.emitTrigger(TriggerEvent)` — a public emit guarded by a
  per-id cooldown map (reserved `health` id, cooldown e.g. 5 min) so the
  controller can push health events without a detector instance.
- [ ] **Step 4:** Tests — watchdog unit tests (design §Verification); pipeline
  `emitTrigger` cooldown; alert-text tests for stall/recovered.
- [ ] **Step 5:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: health watchdog core + pipeline emitTrigger"
  ```

### Task 2: Controller wiring + UI state

**Files:**
- Modify: `security_cam/lib/state/monitor_controller.dart`
- Modify: `security_cam/lib/ui/monitor_screen.dart`
- Modify: `security_cam/lib/core/settings.dart`
- Modify: `security_cam/test/monitor_controller_test.dart`

- [ ] **Step 1:** In `start()`: build the watchdog (`onEpisode` → emit health
  trigger via `pipeline.emitTrigger` with `detail: stall/recovered`, set/clear
  `healthStalled` + `healthNote`, notifyListeners); tap the dispatcher inputs to
  call `noteFrame`/`noteAudio`; arm a `healthCheckInterval` `Timer.periodic`
  (injectable; default 5 s) calling `watchdog.check(now)`. `stop()`/`_disposeRuntime`
  cancels + resets. `dispose()` cancels the timer.
- [ ] **Step 2:** `AppSettings.defaults()` gains an enabled `health` DetectorConfig
  (routing like motion; cooldown 5 min) so users can change its route — the
  watchdog itself is not gated by the config's `enabled` in v1, but its routing
  comes from `DetectorConfig.routeToChannelIds` (via the trigger's type).
- [ ] **Step 3:** `MonitorScreen` shows a red banner while `healthStalled` (via the
  existing `ListenableBuilder`).
- [ ] **Step 4:** Controller tests — flowing frames → silent; stopped frame stream
  → health trigger + `healthStalled`; resume → recovery + cleared; `stop()` resets
  the watchdog; starting state never trips before first data.
- [ ] **Step 5:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: wire health watchdog into MonitorController + UI banner"
  ```

---

## Self-Review notes

- **Spec coverage:** watchdog ✓; controller wiring ✓; health trigger + text ✓;
  UI banner ✓; no periodic heartbeats ✓.
- **Key decisions:** watchdog arms on first data (no false trip during starting);
  episode flags prevent alert spam; health events are emitted by the controller
  (a stalled camera can't run detectors); routing reuses the `health` config's
  route list.
- **Blast radius:** additive — controller lifecycle, one new pure-Dart class,
  settings default, monitor UI banner.
- **Deferred:** periodic heartbeats, schedule-paused-aware logic, device-level
  health.