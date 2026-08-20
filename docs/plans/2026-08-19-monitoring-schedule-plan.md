# Monitoring Schedule (Excluded Times) — Implementation Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [ ]`)
> syntax for tracking. This plan is part of the multi-workstream feature batch
> (2026-08-19); execute after go-ahead.

**Goal:** Recurring time windows during which monitoring is excluded, enforced with
auto-stop on entry and auto-resume on exit (if it was running before).

**Architecture:** `ScheduleWindow` model + pure `SchedulePolicy.isExcluded()`;
`MonitorController` enforces via a `scheduleCheckInterval` `Timer.periodic`
(mirroring `_restartPurgeTimer`); settings UI lists/edits windows.

**Spec:** `docs/plans/2026-08-19-monitoring-schedule-design.md`

**Execution rule:** Prefer Linux desktop (`flutter test`) for all iteration; pure Dart.

---

### Task 1: `ScheduleWindow` model + `SchedulePolicy`

**Files:**
- Create: `security_cam/lib/core/schedule_policy.dart`
- Modify: `security_cam/lib/core/settings.dart`
- Create: `security_cam/test/schedule_policy_test.dart`
- Modify: `security_cam/test/settings_test.dart`

- [ ] **Step 1:** `ScheduleWindow` (pure Dart): `id`, `days` (Mon=1 … Sun=64
  bitmask), `startHour`/`startMinute`, `endHour`/`endMinute` (as ints to keep
  `lib/core` Flutter-free), `enabled`; `toJson`/`fromJson`/`copyWith`;
  `bool matches(DateTime now)` (weekday bit + time range; handles `end <= start`
  as overnight wrap; `start == end` as 24 h).
- [ ] **Step 2:** `SchedulePolicy.isExcluded(List<ScheduleWindow>, DateTime now)` —
  true iff any enabled window `matches(now)`.
- [ ] **Step 3:** Add `AppSettings.scheduleExclusions: List<ScheduleWindow>`
  (default `[]`) with `copyWith`/`toJson`/`fromJson`.
- [ ] **Step 4:** Tests — policy edge cases (inside/outside, weekday, overnight
  wrap, 24 h window, disabled, empty list); settings JSON round-trip.
- [ ] **Step 5:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: schedule window model + exclusion policy"
  ```

### Task 2: Controller enforcement (auto-stop / auto-resume)

**Files:**
- Modify: `security_cam/lib/state/monitor_controller.dart`
- Modify: `security_cam/test/monitor_controller_test.dart`

- [ ] **Step 1:** Add injectable `Duration? scheduleCheckInterval` to the
  constructor (default e.g. `Duration(minutes: 1)`; null disables).
- [ ] **Step 2:** Add `bool _schedulePaused = false`; `bool get schedulePaused`;
  `String? get scheduleNote`. `Timer? _scheduleTimer` +
  `_restartScheduleTimer()` following `_restartPurgeTimer()` (armed on `init`/
  `updateSettings`, cancelled on `dispose`). Tick body:
  `isExcluded(now)` → if monitoring, set `_schedulePaused = true` and `stop()`;
  `!isExcluded(now)` → if `_schedulePaused`, clear flag and `start()`.
- [ ] **Step 3:** Gate manual `start()`: when excluded, set `scheduleNote` and
  return without starting; manual `stop()` clears `_schedulePaused` + `scheduleNote`.
- [ ] **Step 4:** Controller tests with short injected interval + controllable
  "now" (inject a `DateTime Function()? clock` or test via
  `isExcluded`-shaped settings) — assert auto-stop on entering a window,
  auto-resume on leaving, blocked manual start, manual stop clears resume flag.
- [ ] **Step 5:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: schedule auto-stop/auto-resume in MonitorController"
  ```

### Task 3: Settings UI

**Files:**
- Modify: `security_cam/lib/ui/settings_screen.dart`
- Modify: `security_cam/test/settings_screen_test.dart`

- [ ] **Step 1:** "Schedule" card: list windows (enable `Switch`, Mon–Sun day
  chips, start/end time pickers, delete), "Add window" button. Keys
  `ValueKey('scheduleWindow_${id}')` / `ValueKey('scheduleAddWindow')`. Draft
  edits via `_draft.copyWith(scheduleExclusions: ...)`; saved through the normal
  save path.
- [ ] **Step 2:** Widget tests — add/edit/remove/disable a window; round-trip
  through save; empty state.
- [ ] **Step 3:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: settings UI for schedule exclusions"
  ```

---

## Self-Review notes

- **Spec coverage:** recurring windows ✓; auto-stop ✓; auto-resume-if-running ✓;
  blocked manual start ✓; settings UI ✓; policy + controller tests ✓.
- **Key decision:** enforcement tick is a 1-minute `Timer.periodic`; latency ≤ 60 s
  is acceptable and tests inject short intervals.
- **Blast radius:** settings model, controller lifecycle, settings UI. Detectors,
  channels, pipeline, and events are untouched.
- **UX safety:** auto-resume only when it was running before the window, with a
  visible `schedulePaused` state and an empty-list escape hatch.