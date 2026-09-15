package io.securitycam.level2.storage

import io.securitycam.level2.core.Snapshot
import java.io.File
import java.io.IOException
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

    /**
     * Deterministic collision-safe mapping: the sanitized stem plus an 8-hex
     * hash of the full original name, so `a/b.png` and `a_b.png` land on
     * different files while save/load/delete always agree.
     */
    internal fun pathFor(name: String): String {
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && !it.contains('/') }
        val stem = if (ext != null) name.dropLast(ext.length + 1) else name
        val safeStem = stem.replace(Regex("[^A-Za-z0-9._-]"), "_").takeIf { it.isNotEmpty() } ?: "_"
        val hash = "%08x".format(name.hashCode())
        val safeExt = ext?.replace(Regex("[^A-Za-z0-9]"), "")?.takeIf { it.isNotEmpty() }
        val fileName = if (safeExt != null) "$safeStem-$hash.$safeExt" else "$safeStem-$hash"
        return "$directoryPath/$fileName"
    }

    internal fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
    }

    override suspend fun save(snapshot: Snapshot): String = withContext(Dispatchers.IO) {
        val dir = File(directoryPath)
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw IOException("cannot create snapshot directory: $directoryPath")
        }
        val file = File(pathFor(snapshot.name))
        // Atomic write: fsync a temp sibling then rename, so a crash can
        // never leave a half-written snapshot behind.
        val tmp = File.createTempFile("snap-", ".tmp", dir)
        try {
            tmp.writeBytes(snapshot.bytes)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(file)) {
                // Cross-FS fallback: copy + delete.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
        file.absolutePath
    }

    override suspend fun load(name: String): Snapshot? = withContext(Dispatchers.IO) {
        val file = File(pathFor(name))
        if (!file.exists()) return@withContext null
        Snapshot(bytes = file.readBytes(), mimeType = mimeFor(name), name = name)
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        val file = File(pathFor(name))
        if (file.exists()) file.delete()
    }
}
