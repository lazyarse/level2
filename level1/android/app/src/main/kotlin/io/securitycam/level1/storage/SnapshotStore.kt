package io.securitycam.level1.storage

import io.securitycam.level1.core.Snapshot

/** Snapshot persistence contract (concrete store lands in Phase 4). */
interface SnapshotStore {
    suspend fun save(snapshot: Snapshot): String

    suspend fun load(name: String): Snapshot?

    suspend fun delete(name: String)
}