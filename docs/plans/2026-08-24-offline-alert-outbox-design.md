# Offline Alert Outbox — Design

Date: 2026-08-24
Status: Draft

## Goal

Alerts must survive device offline periods. Today a channel send that fails all
retries (no SIM / Wi-Fi down at event time) is permanently lost — the event row
just records `"failed"`. This design queues **notification data per
(event × channel)** in an internal on-device queue and delivers it whenever the
device regains connectivity, even across process death or reboot.

Decisions (2026-08-24):

- Single shared outbox table for notifications **and** cloud-backup uploads
  (`kind = "notify" | "backup"`); one drain path, one retry policy.
- Drain owned by **WorkManager** with `NetworkType.CONNECTED` constraint —
  guaranteed delivery after reboot/process death (new `androidx.work` dep).
- Google Drive excluded from future backup work for privacy reasons; not
  relevant here but recorded since this doc establishes the outbox both feed from.

## Current state (verified from code, 2026-08-24)

- `EventPipeline.sendWithRetry()` (`event/EventPipeline.kt:88`) attempts each
  channel up to 3× with 1s/2s/4s backoff, then records `"failed"` in
  `channelStatuses` of the stored `RecordedEvent`. No persistence of the payload.
- Snapshots are already written to `FileSnapshotStore` **before** channels run
  (`handleBatch`, lines 43–49), so queued alerts can reference snapshot bytes by
  name instead of duplicating them.
- `AlertMessage` (`core/Channel.kt:51`) = timestamp + triggerType + text +
  optional `Snapshot(bytes, mime, name)`.
- Channels are rebuilt per-send from `ChannelConfig` via `ChannelRegistry`
  factories, so a queued row only needs the channel *id*.
- Room DB (`storage/EventStore.kt:82`) is schema **version 4** → outbox lands as
  v5 via `MIGRATION_4_5`. WorkManager is not yet a dependency.

## Design

### 1. Schema: `outbox` table (v5)

```
CREATE TABLE outbox (
  id            INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  createdAt     INTEGER NOT NULL,          -- epoch ms, FIFO order
  kind          TEXT    NOT NULL,          -- "notify" | "backup"
  channelId     TEXT,                      -- notify: target channel id
  triggerType   TEXT,                      -- notify: label rendering / dedupe info
  eventTime     INTEGER,                   -- notify: original event ts
  text          TEXT,                      -- notify: pre-rendered alert text
  snapshotName  TEXT,                      -- nullable ref into FileSnapshotStore
  mediaPath     TEXT,                      -- backup: absolute file to upload
  remotePath    TEXT,                      -- backup: destination key/path
  attempts      INTEGER NOT NULL DEFAULT 0,
  lastAttemptAt INTEGER
);
CREATE INDEX index_outbox_createdAt ON outbox(createdAt);
```

One row per (event × channel) for notifies; one per media item for backups.
Columns are plain — no opaque JSON blob — so rows are debuggable via `adb shell
sqlite3`.

### 2. `OutboxStore` (`channels/AlertOutbox.kt`)

Thin DAO wrapper over the Room entity:

```kotlin
class OutboxStore(db: AppDatabase) {
    suspend fun enqueue(row: OutboxEntity)
    fun peekBatch(limit: Int): List<OutboxEntity>       // oldest-first
    suspend fun markAttempted(id: Long, attempts: Int, now: Instant)
    suspend fun delete(id: Long)
    suspend fun dropExpired(maxAttempts: Int, maxAge: Duration)
    val pendingCountFlow: Flow<Int>                     // UI badge
}
```

### 3. Enqueue path (`EventPipeline`)

- In `sendWithRetry`, when all attempts fail: build an `OutboxEntity(kind =
  "notify", channelId = target.id, triggerType, eventTime, text,
  snapshotName = snapshot?.name)` and insert. Return status `"queued"` instead
  of `"failed"` (stored into `channelStatuses`).
- Pure logic (decide enqueue vs fail) extracted into a testable function taking
  an `enqueue: suspend (OutboxEntity) -> Unit` seam.

### 4. Connectivity + drain

- **`ConnectivityMonitor`** (`core/ConnectivityMonitor.kt`): registers
  `ConnectivityManager.registerDefaultNetworkCallback` (API 24+ safe), exposes
  `isOnline: StateFlow<Boolean>`. Used for UI state; WorkManager handles the
  actual scheduling.
- **`OutboxWorker`** (`androidx.work.CoroutineWorker`): constraints
  `NetworkType.CONNECTED`, backoff `EXPONENTIAL` 30 s. Per row:
  - notify: resolve `ChannelFactory(channelConfig.type)` → rebuild
    `AlertMessage(timestamp = eventTime, triggerType, text, snapshot =
    snapshotName?.let { snapshotStore.load(it) })` → `channel.send()`.
  - success → delete row + **update the stored event's `channelStatuses`**
    entry from `"queued"` to `"delivered"` (events table gains nothing new;
    statuses JSON already exists).
  - failure → increment `attempts`; worker re-enqueues itself while rows remain.
- Expiry: rows with `attempts >= 5` or age > 24 h are dropped (constants in one
  object, later configurable). Dropped notify rows flip their event status to
  `"failed (expired)"`.
- Scheduling: worker enqueued once at app start (`Level1App.onCreate`) with a
  unique name (`KEEP` policy) — WorkManager re-runs it whenever connectivity
  returns; no manual network listening needed for delivery.

### 5. UI

- Events list: small "queued" chip when any `channelStatuses` value is
  `"queued"`; Settings shows outbox depth ("N alerts waiting") near channels.

## Verification

- Unit: outbox DAO round-trip (Robolectric/in-memory Room); EventPipeline enqueues
  on exhausted retries and records `"queued"`; expiry rules; message rebuild from
  row (snapshot reload included); Worker logic via injected sender fake.
- Migration test 4→5 preserves events table.
- Instrumentation (optional): airplane-mode toggle → event fires → alert queued →
  reconnect → Telegram receives + status flips to delivered.

## Risks

- Snapshot files may be purged by retention before a queued alert drains →
  `snapshotStore.load` returns null; send proceeds text-only (acceptable).
- Channel config deleted/renamed before drain → factory lookup fails; row
  expires naturally rather than erroring forever.
- WorkManager adds a dependency (~2 MB) — accepted deliberately for delivery
  guarantees.
