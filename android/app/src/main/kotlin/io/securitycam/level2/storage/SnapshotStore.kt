package io.securitycam.level2.storage

import io.securitycam.level2.core.Snapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Snapshot persistence contract. */
interface SnapshotStore {
    suspend fun save(snapshot: Snapshot): String

    suspend fun load(name: String): Snapshot?

    suspend fun delete(name: String)
}

/** File-backed snapshot store under [directoryPath] (port of the Dart store). */
class FileSnapshotStore(private val directoryPath: String) : SnapshotStore {

    private fun pathFor(name: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$directoryPath/$safe"
    }

    override suspend fun save(snapshot: Snapshot): String = withContext(Dispatchers.IO) {
        val dir = File(directoryPath)
        if (!dir.exists()) dir.mkdirs()
        val file = File(pathFor(snapshot.name))
        file.writeBytes(snapshot.bytes)
        file.absolutePath
    }

    override suspend fun load(name: String): Snapshot? = withContext(Dispatchers.IO) {
        val file = File(pathFor(name))
        if (!file.exists()) return@withContext null
        val mime = when {
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") -> "image/jpeg"
            else -> "application/octet-stream"
        }
        Snapshot(bytes = file.readBytes(), mimeType = mime, name = name)
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        val file = File(pathFor(name))
        if (file.exists()) file.delete()
    }
}