# Channel Test-Alert (sendTest) — Implementation Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [ ]`)
> syntax for tracking. This plan is part of the multi-workstream feature batch
> (2026-08-19); execute after go-ahead.

**Goal:** Surface the already-implemented `Channel.sendTest()` through a per-channel
"Send test" button in settings, backed by a `MonitorController.sendTest` that
builds the channel from in-memory (secrets-complete) settings.

**Architecture:** Pure-Dart `sendTest` on the controller using `channelRegistry` +
`buildChannelSettings`; UI button gated on `validate()` of the **draft** settings;
SnackBar result feedback.

**Spec:** `docs/plans/2026-08-19-channel-sendtest-design.md`

**Execution rule:** Prefer Linux desktop (`flutter test`) for all iteration; pure Dart.

---

### Task 1: `MonitorController.sendTest`

**Files:**
- Modify: `security_cam/lib/state/monitor_controller.dart`
- Modify: `security_cam/test/monitor_controller_test.dart`

- [ ] **Step 1:** Implement `Future<String> sendTest(ChannelConfig config)`:
  build settings via `buildChannelSettings(config.type, config.settingsJson)`,
  channel via `channelRegistry[config.type]!(config)`; return `'invalid: <reason>'`
  when `validate()` is non-null; else `await channel.sendTest()` returning
  `'delivered'` or `'failed: <error>'` (catch).
- [ ] **Step 2:** Unit tests — `log` channel → `'delivered'` (in-memory, no
  network); webhook config with empty URL → `'invalid:'` and no HTTP (use a
  counting `http.Client` to assert zero requests); a config whose channel throws →
  `'failed:'`.
- [ ] **Step 3:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: controller sendTest builds channel from in-memory settings"
  ```

### Task 2: Settings UI button + SnackBar

**Files:**
- Modify: `security_cam/lib/ui/settings_screen.dart`
- Modify: `security_cam/test/settings_screen_test.dart`

- [ ] **Step 1:** In `_channelCard` (`settings_screen.dart:425-452`), after
  `..._channelFields(config)`, add an `OutlinedButton` "Send test" with
  `ValueKey('sendTest_${config.id}')`, disabled while a local `_testingChannelId`
  is set.
- [ ] **Step 2:** Enabled state: rebuild draft channel settings (mirroring
  `_save()`'s controller reads) → `validate()`; disabled when non-null or while
  in flight. On tap: `final r = await widget.controller.sendTest(config);` show
  SnackBar with the result.
- [ ] **Step 3:** Widget tests — button present for each visible channel (not
  `log`); disabled for an invalid draft (e.g. telegram without token); tapping a
  valid-but-cheap channel shows a SnackBar (log is hidden from UI, so assert the
  button wiring against a channel whose `validate()` passes on draft — or gate via
  the controller's returned string in a fake).
- [ ] **Step 4:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: per-channel Send test button with validation gating"
  ```

---

## Self-Review notes

- **Spec coverage:** controller method ✓; UI button ✓; draft-based validation
  gating ✓; SnackBar feedback ✓; log channel hidden ✓.
- **Key decision:** build from the **draft** for validation and from the saved
  config for the send; both paths are in-memory so secrets are complete.
- **Blast radius:** settings screen + controller; channel implementations and
  event delivery are untouched.
- **Network safety:** unit tests never hit the network (log is in-memory; invalid
  configs short-circuit; throwing channel is injected).