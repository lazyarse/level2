# Cloud Backup of Clips & Snapshots — Design

Date: 2026-08-24
Status: Draft

## Goal

Privacy-first off-device backup of event clips and snapshots: evidence survives
theft or destruction of the phone. Uploads are end-to-end TLS to **infrastructure
the user controls**, queued through the shared offline outbox
(see `2026-08-24-offline-alert-outbox-design.md`), and never block the event
pipeline.

Decisions (2026-08-24):

- Backends in scope: **WebDAV** (Nextcloud, nginx/Apache, InfiniCloud, …) and
  **S3-compatible** (AWS S3, Backblaze B2, Minio, Wasabi) behind one interface.
- **Google Drive explicitly rejected** on privacy grounds.
- Shared outbox (`kind = "backup"`), WorkManager-drained like notifications.

## Current state (verified from code, 2026-08-24)

- Clips: `VideoClipRecorder.exportClip()` writes into MediaStore
  `Movies/level1` with `videoFileName(triggerMs, cameraName)`; the recorded
  event stores the display name only (`RecordedEvent.videoName`).
- Snapshots: `EventPipeline.handleBatch` → `FileSnapshotStore.save()` under
  `filesDir/snapshots/<name>`.
- Secrets pattern to copy: live-view password is routed through
  `EncryptedSecretStore` via `SettingsStore.injectLiveViewSecret()`
  (`storage/SettingsStore.kt:130-141`) — settings blob keeps an empty string,
  the real value lives in the Keystore-backed store.
- Outbox v5 schema (designed alongside this doc) already reserves
  `mediaPath` / `remotePath` columns.

## Design

### 1. Settings model

```kotlin
data class CloudBackupSettings(
    val enabled: Boolean = false,
    val backend: String = "webdav",        // "webdav" | "s3"
    val serverUrl: String = "",            // webdav base URL or S3 endpoint
    val bucketOrPath: String = "",         // s3 bucket | webdav remote dir
    val region: String = "",               // s3 only
    val username: String = "",             // webdav user | s3 access key id
    val password: String = "",             // → SecretStore (SECRET_FIELD)
    val backupClips: Boolean = true,
    val backupSnapshots: Boolean = true,
    val deleteLocalAfterUpload: Boolean = false,
) { companion object { const val SECRET_FIELD = "password" } }
```

Wired as `AppSettings.cloudBackup`, JSON parity + secret routing copied from the
live-view block. Settings UI: new "Cloud backup" collapsible section with
backend toggle chips, fields shown per backend, and a **Test connection**
button (backend `validate()` — PROPFIND for WebDAV, HEAD bucket for S3).

### 2. Uploader interface

```kotlin
interface CloudUploader {
    val backendId: String
    suspend fun validate(): Boolean          // test-connection probe
    suspend fun upload(local: File, remoteKey: String): Boolean
}
object CloudUploaderRegistry {
    fun forBackend(settings: CloudBackupSettings): CloudUploader?
}
```

- **WebDAV**: `PUT <serverUrl>/<bucketOrPath>/<remoteKey>` with Basic auth over
  HTTPS (reject plaintext http unless URL host is a LAN address). Uses
  `HttpURLConnection`; no new dependency. MKCOL parent dirs best-effort.
- **S3**: SigV4 request signing implemented locally (~150 lines, well-specified)
  using `javax.crypto.Mac` — avoids pulling the AWS SDK. Single PUT per object;
  no multipart in v1 (clips are ≤ ~30 s at low bitrate).

Remote key layout mirrors local naming so any file browser works:
`<cameraName>/<yyyy-MM-dd>/<mediaFileName>`.

### 3. Enqueue points

- Snapshots: right after `snapshotStore.save(snapshot)` succeeds in
  `EventPipeline.handleBatch` — enqueue `(kind="backup", mediaPath=…,
  remoteKey=…)`.
- Clips: where export completes (`MonitoringRuntime.captureVideo` callback /
  batcher path) with the MediaStore-resolved real path.
- Both guarded by `CloudBackupSettings.enabled` + per-type flags read at enqueue
  time from the same settings snapshot that built the pipeline.

### 4. Drain

`OutboxWorker` handles `kind = "backup"` rows exactly like notifies:
resolve uploader from current settings → `upload()` → success deletes row;
failure increments attempts; expiry identical (5 attempts / 24 h). Uploads run
only under the CONNECTED constraint; Wi-Fi-only preference can be added later
via `NetworkRequest` capabilities if wanted.

### 5. Local retention interplay

Retention purge (`purgeOldEvents`) must not delete files still referenced by
pending backup rows: purge query joins outbox on media identity and skips them
(or uploads first when `deleteLocalAfterUpload`). Simplest correct rule: **a
pending backup row pins its file**; row expiry releases it.

## Verification

- Unit: SigV4 signer against AWS published test vectors; WebDAV PUT/PROPFIND via
  MockWebServer-style fake; key-layout function pure tests; enqueue guards
  (disabled / type-flag off); retention-purge pinning.
- Instrumentation/manual: enable against a local Nextcloud container +
  Minio container; trigger motion event; confirm clip+snapshot appear under
  expected keys; airplane-mode mid-upload → resumes after reconnect.

## Risks

- Large clip uploads on metered connections — mitigated later by a
  "Wi-Fi only" toggle (out of scope v1).
- Clock skew breaks SigV4 — surface validate() errors clearly in Test connection.
- MediaStore path resolution latency on first access after reboot — acceptable
  inside a Worker.
