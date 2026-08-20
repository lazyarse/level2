# History: Timeline + Gallery — Implementation Plan

> **For agentic workers:** implement this plan task-by-task using checkbox (`- [ ]`)
> syntax for tracking. This plan is part of the multi-workstream feature batch
> (2026-08-19); execute after go-ahead.

**Goal:** A History tab (Timeline | Gallery) showing day-scoped event activity and
a grid of event snapshots, reusing extracted shared widgets.

**Architecture:** New ranged/snapshot-filtered event queries; extracted
`SnapshotThumb` / `SnapshotViewerDialog` / event-detail widgets; day-scoped
Timeline + Gallery sub-tabs in a new History navigation destination.

**Spec:** `docs/plans/2026-08-19-history-timeline-gallery-design.md`

**Execution rule:** Prefer Linux desktop (`flutter test`) for all iteration; pure Dart/Flutter.

---

### Task 1: Event query extensions

**Files:**
- Modify: `security_cam/lib/storage/event_log.dart`
- Modify: `security_cam/test/event_log_test.dart`

- [ ] **Step 1:** Add `Future<List<RecordedEventRow>> between(DateTime start,
  DateTime end, {int? limit, bool? withSnapshots})` — WHERE `timestamp >= start
  AND timestamp < end`, optional `snapshot_name IS NOT NULL`, `ORDER BY timestamp
  DESC`, optional limit. Reuse the existing `_rowFromMap`.
- [ ] **Step 2:** Tests — bounds inclusive/exclusive; snapshot filter; ordering;
  limit; empty range.
- [ ] **Step 3:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: ranged + snapshot-filtered event log queries"
  ```

### Task 2: Extract shared widgets

**Files:**
- Create: `security_cam/lib/ui/widgets/snapshot_thumb.dart`
- Create: `security_cam/lib/ui/widgets/snapshot_viewer.dart`
- Modify: `security_cam/lib/ui/events_screen.dart`
- Modify: `security_cam/test/events_screen_test.dart`

- [ ] **Step 1:** `SnapshotThumb` — configurable size, `Image.memory` `BoxFit.cover`,
  capped in-memory byte cache keyed by snapshot name; drop-in for `_SnapshotThumb`.
- [ ] **Step 2:** `SnapshotViewerDialog` — the `InteractiveViewer` dialog (from
  `events_screen.dart:155-226`), invoked with a `Snapshot` (or name+store).
- [ ] **Step 3:** Refactor `EventsScreen` to use both (behavior unchanged);
  extract the tap→detail handler so Timeline/Gallery reuse it.
- [ ] **Step 4:** Tests — `SnapshotThumb` renders bytes and caches; viewer dialog
  opens; EventsScreen existing tests stay green after the refactor.
- [ ] **Step 5:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "refactor: extract shared snapshot thumb + viewer widgets"
  ```

### Task 3: Timeline sub-tab

**Files:**
- Create: `security_cam/lib/ui/history/timeline_tab.dart`
- Modify: `security_cam/test/timeline_tab_test.dart` (new)

- [ ] **Step 1:** `TimelineTab({required Future<List<RecordedEventRow>> Function(DateTime day) loader, required SnapshotStore, required openVideo})` — day header (‹/›/date picker), per-hour activity bars, vertical hour-bucketed event list, tap → shared detail.
- [ ] **Step 2:** Tests — renders hour buckets; event at correct `HH:mm`; activity bar counts; day navigation calls the loader with the new range.
- [ ] **Step 3:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: day-scoped timeline tab"
  ```

### Task 4: Gallery sub-tab + History shell

**Files:**
- Create: `security_cam/lib/ui/history/gallery_tab.dart`
- Create: `security_cam/lib/ui/history/history_tab.dart`
- Modify: `security_cam/lib/ui/app.dart`
- Modify: `security_cam/lib/main.dart`
- Modify: `security_cam/test/gallery_tab_test.dart` (new)
- Modify: `security_cam/test/app_test.dart` / shell test

- [ ] **Step 1:** `GalleryTab({loader (snapshot-filtered, day-scoped), snapshotStore})` — `GridView.builder` (3 columns) of `SnapshotThumb`; tap → `SnapshotViewerDialog`.
- [ ] **Step 2:** `HistoryTab` — `DefaultTabController` with Timeline | Gallery tabs, sharing the selected day (lifted to `HistoryTab` state).
- [ ] **Step 3:** Shell: 4th `NavigationDestination` **History**; `main.dart` wires the `between`-based loaders (and the events `reloadTick`-style refresh for History).
- [ ] **Step 4:** Tests — Gallery renders thumbnails for rows with snapshots, opens viewer, day nav; HistoryTab switches sub-tabs; shell shows 4 destinations and switches to History.
- [ ] **Step 5:** Verify + commit:
  ```bash
  date -R && cd security_cam && flutter test && flutter analyze
  git add -A && git commit -m "feat: History tab with timeline and snapshot gallery"
  ```

---

## Self-Review notes

- **Spec coverage:** ranged queries ✓; extracted shared widgets ✓; Timeline ✓;
  Gallery ✓; History shell tab ✓.
- **Key decision:** gallery is events-driven (consistent with retention purge) and
  day-scoped in v1; thumbnails use an ephemeral in-memory byte cache.
- **Blast radius:** additive query methods + new UI + shell nav; EventsScreen is
  refactored but behavior-identical (its tests guard the refactor).
- **Deferred:** persistent thumbnail variants, clip browser, cross-day timeline.