# History: Timeline + Gallery — Design

Date: 2026-08-19
Status: Draft (implementation plan follows)

## Goal

Add a **History** tab (4th bottom-nav destination) with two sub-tabs:
- **Timeline** — a time-ordered, day-scoped view of when events happened, with
  per-hour activity bars, tapping an event opens its snapshot/video details.
- **Gallery** — a grid of event snapshot thumbnails, tapping opens the full-size
  viewer.

Both read from the existing event log and snapshot store; no new storage format.

## Current state (verified from code, 2026-08-19)

- **Events UI** — `EventsScreen` (`lib/ui/events_screen.dart`, 227 lines): a
  `FutureBuilder` + `ListView.separated` of rows; leading 48×48 `_SnapshotThumb`
  (full bytes loaded via `SnapshotStore.load`, `Image.memory(48×48)`), title
  `'$typeLabel · score …'`, subtitle `'${timestamp.toLocal()} — camera — statuses'`,
  trailing play button for video (`openVideo`). Tap on thumb opens a `Dialog` with
  `InteractiveViewer(maxScale: 8)` on a 480×360 `Image.memory` (lines 155-226).
- **Event query** — `SqliteEventLog.recent({limit})` (`lib/storage/event_log.dart:110`)
  orders by `timestamp DESC`, **no WHERE filter / cursor**. `RecordedEventRow`
  carries `timestamp, cameraName, triggerType, score, snapshotName, videoName,
  channelStatuses, triggerTypes, id`; timestamps stored ISO-8601 (UTC).
- **Snapshot store** — `SnapshotStore` (`lib/storage/snapshot_store.dart`): `save/
  load/delete` only, **no `list()`**; files named `mediaFileName(...)` under
  `snapshots/`. No thumbnail-size variant (grid would load full bytes).
- **Navigation** — `_Shell` (`lib/ui/app.dart`): `IndexedStack` + `NavigationBar`
  with 3 destinations (Monitor, Events, Settings); Events reloads via a `reloadTick`
  bump. Only pushed route today is `RegionEditorScreen`.
- **No date-format helpers, no `intl`, no `GridView`** anywhere in `lib/`.

## Design

### 1. Data access

- `SqliteEventLog.between(DateTime start, DateTime end, {int? limit})` — ranged
  query on `timestamp` for the day-scoped timeline.
- **Gallery is events-driven**: query rows where `snapshotName != null` (add
  `withSnapshots` filter to `between`/`recent`), so gallery content mirrors event
  retention/purge instead of exposing orphan files.
- `SnapshotStore` stays unchanged (load-by-name is sufficient; the grid reuses the
  full bytes, matching the existing events-row pattern).

### 2. Shared widgets (extracted from `EventsScreen`)

- `lib/ui/widgets/snapshot_thumb.dart` — promoted `_SnapshotThumb` with a
  `size` parameter and a small in-memory byte cache (`Map<String, Uint8List>`,
  LRU-ish / capped) so grids don't re-decode on every rebuild.
- `lib/ui/widgets/snapshot_viewer.dart` — promoted `InteractiveViewer` dialog
  (`SnapshotViewerDialog`), shared by Events, Timeline, and Gallery.
- `lib/ui/widgets/event_detail.dart` — extracted tap-detail surface (snapshot
  viewer + video open) reused across all three screens.
- EventsScreen is refactored to use these without behavior change.

### 3. Timeline sub-tab

- Day-scoped: a header row with ‹ / date-label / › plus a date picker, defaulting
  to today. Below, a **vertical timeline**: hour buckets (00:00…23:00) with a thin
  axis, each event pinned at its `HH:mm` with the trigger label + score; a
  per-hour activity bar (count of events that hour) at the top.
- Events load via `eventLog.between(dayStart, dayEnd)`; tap → shared event detail.
- No scrolling continuity between days (explicit day navigation).

### 4. Gallery sub-tab

- `GridView.builder` (3 columns, `aspectRatio: 1`) of `SnapshotThumb` for the
  selected day's events that have a `snapshotName`; tap → `SnapshotViewerDialog`.
- Same day navigation as Timeline; a "All days" mode optional (v1: day-scoped to
  match Timeline, which bounds the query).

### 5. Shell integration

- 4th `NavigationDestination` **History** (`Icons.history`, keeping Events on
  `Icons.event_note` or similar) with a `DefaultTabController` hosting the
  Timeline | Gallery `TabBar`. The `reloadTick` mechanism extends to History so it
  refreshes on tab switch.

## Verification

- **Query tests**: `between()` bounds inclusive/exclusive semantics; snapshot-name
  filter; ordering; limit.
- **Widget tests**: Timeline renders day buckets + activity bars; tapping opens
  the viewer; Gallery grid renders thumbnails; day navigation changes the queried
  range; History tab exists and switches sub-tabs; EventsScreen refactor keeps its
  existing tests green.
- **Existing suite**: `flutter test` + `flutter analyze` green on Linux desktop.

## Deferred / not in this phase

- Multi-day contiguous timeline / infinite scroll (explicit day navigation is v1).
- Gallery across all days / search / filter by trigger type.
- Persistent thumbnail files (the in-memory cache is ephemeral; a persisted
  resized variant needs an image-lib dependency).
- Clip browser (video thumbnails) — VideoStore has no listing API; deferred.

## Risks

- Low-medium. Data layer is additive (new query methods); the UI is greenfield but
  reuses extracted widgets. Main risks: grid performance on large days (mitigated
  by the byte cache + day scoping) and `reloadTick` plumbing for the new tab.