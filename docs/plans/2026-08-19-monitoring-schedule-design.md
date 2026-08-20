# Monitoring Schedule (Excluded Times) — Design

Date: 2026-08-19
Status: Draft (implementation plan follows)

## Goal

Let the user define recurring time windows during which **monitoring is excluded**
— the app stops detecting, recording, and alerting during those windows, then
resumes automatically. Decision (2026-08-19): no separate "quiet hours" concept;
a schedule *excludes* times from recording/monitoring, with auto-stop on entering
a window and auto-resume on leaving (if it was running before).

## Current state (verified from code, 2026-08-19)

- **No schedule concept exists.** `AppSettings` (`lib/core/settings.dart:90-119`)
  has no time-window field; there is no date/time helper (no `intl`).
- **Monitoring lifecycle** is manual: `MonitorController.start()`/`stop()`
  (`lib/state/monitor_controller.dart:142-246`) drive `MonitorState`; while
  monitoring, the pipeline, batcher, and video ring buffer run continuously and
  event-driven recording is always active.
- **Periodic timer pattern to reuse:** `_purgeTimer` +
  `_restartPurgeTimer()` (`lib/state/monitor_controller.dart:49,86-103,207-215`),
  with an injectable `purgeInterval` (null disables, used by tests).

## Design

### 1. Data model: `ScheduleWindow`

```dart
class ScheduleWindow {
  final String id;          // stable identity for keys/serialization
  final int days;           // bitmask: bit0=Mon … bit6=Sun (0 = never, per-window)
  final TimeOfDay start;    // inclusive
  final TimeOfDay end;      // exclusive; end == start => 24h window; wraps at midnight
  final bool enabled;       // keep a disabled window for later use
}
```

Persisted as `AppSettings.scheduleExclusions: List<ScheduleWindow>` (default `[]`),
with `copyWith` + JSON round-trip. `TimeOfDay` is Flutter; the model lives in
`lib/core/` which must stay pure Dart — store `hour`/`minute` ints in the model
(or use the ints directly as the model fields) so `lib/core` keeps zero Flutter
deps. `DateTime`/`Duration` time math stays pure Dart.

### 2. Pure policy: `SchedulePolicy`

New `lib/core/schedule_policy.dart`:
- `bool isExcluded(List<ScheduleWindow> windows, DateTime now)` — true iff `now`
  falls inside any enabled window on its matching weekday. Handles overnight wrap
  (e.g. 22:00→06:00) and the 24 h `start == end` case.
- A `ScheduleWindow.matches(DateTime)` helper (weekday bit + time range).
- Fully unit-testable with injected `DateTime`s; no timers, no Flutter.

### 3. Controller enforcement: auto-stop + auto-resume

Mirror `_restartPurgeTimer`:
- New injectable `Duration? scheduleCheckInterval` (default ~1 min; null disables
  in tests that don't care).
- A `Timer.periodic` armed on `init`/`updateSettings`, cancelled on `dispose`:
  - `state == monitoring && policy.isExcluded(now)` → set `_schedulePaused = true`
    (remember it was running) and `await stop()` (without notifying an error).
  - `state == idle && _schedulePaused && !policy.isExcluded(now)` → clear the flag
    and `await start()`.
- Manual `start()` is blocked while excluded: if `isExcluded(now)`, set a
  `scheduleNote`/error message ("Monitoring is paused during a scheduled
  exclusion") and do not start.
- Manual `stop()` clears `_schedulePaused`.
- `MonitorState` is unchanged; the UI can show the pause reason via a new
  `bool get schedulePaused` / `String? scheduleNote` on the controller.

### 4. Settings UI: "Schedule" section

- New settings section (card) "Schedule — monitoring pauses during these times",
  listing each window: enable `Switch`, day-of-week chips (Mon–Sun), start/end
  time pickers, delete button; an "Add window" button.
- Keys: `ValueKey('scheduleWindow_${id}')`, `ValueKey('scheduleAddWindow')`.
- Windows persist via the normal `updateSettings` → `SettingsStore` path.

## Verification

- **Policy tests** (`test/schedule_policy_test.dart`): inside/outside a window;
  weekday matching; overnight wrap; 24 h window; disabled window ignored; empty
  list always allowed.
- **Controller tests** (`test/monitor_controller_test.dart`): with a short injected
  `scheduleCheckInterval` and fake clocks — auto-stop on entering a window,
  auto-resume on leaving, manual start blocked while excluded, manual stop clears
  the resume flag.
- **Settings tests**: model JSON round-trip; UI add/edit/remove/disable window.
- **Existing suite**: `flutter test` + `flutter analyze` green on Linux desktop.

## Deferred / not in this phase

- Quiet-hours *routing* (suppress alerts but keep monitoring) — explicitly dropped
  by the 2026-08-19 decision; the schedule only excludes.
- One-off (non-recurring) exclusions or holidays.
- Timezone/clock-change handling beyond what `DateTime` already provides.

## Risks

- Low-medium. Core risk is the auto-resume behavior surprising a user (monitoring
  restarts by itself). Mitigated by (a) only resuming when it was running before
  the window, (b) a visible `schedulePaused`/`scheduleNote` state, and (c) a
  setting toggle (the whole schedule list can be emptied to disable). Timer
  cadence is a 1-minute tick, so enforcement latency is ≤ 60 s — acceptable for
  this feature; tests inject short intervals.