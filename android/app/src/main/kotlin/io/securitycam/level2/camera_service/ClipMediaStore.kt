package io.securitycam.level2.camera_service

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * MediaStore + app-private clip storage. Split out of [VideoClipRecorder]:
 * pure reads/writes that work without the recording session.
 */
object ClipMediaStore {
    private const val TAG = "ClipMediaStore"

    private var appContextRef: Context? = null

    fun attach(ctx: Context) {
        appContextRef = ctx.applicationContext
    }

    /** Writes the final clip into the gallery (MediaStore 29+, DATA on 24-28). */
    internal fun storeInMediaStore(source: File, displayName: String): String? {
        val appContext = appContextRef ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/level2"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = appContext.contentResolver.insert(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                ) ?: return null
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: run {
                    appContext.contentResolver.delete(uri, null, null)
                    return null
                }
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                appContext.contentResolver.update(uri, done, null, null)
                displayName
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "level2"
                )
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, displayName)
                source.inputStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DATA, dest.absolutePath)
                }
                appContext.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                )
                displayName
            }
        } catch (e: Exception) {
            Log.w(TAG, "media store insert failed, falling back to app-private", e)
            try {
                val dest = File(appContext.filesDir, "videos/$displayName")
                dest.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                displayName
            } catch (e2: Exception) {
                Log.w(TAG, "app-private fallback failed", e2)
                null
            }
        }
    }

    private fun queryUriByName(name: String): Uri? {
        val appContext = appContextRef ?: return null
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return try {
            appContext.contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME}=?",
                arrayOf(name),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    Uri.withAppendedPath(collection, c.getLong(0).toString())
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(name: String) {
        val appContext = appContextRef ?: return
        val uri = queryUriByName(name)
        if (uri != null) {
            try {
                appContext.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "media delete failed for $name", e)
            }
        }
        val fallback = File(appContext.filesDir, "videos/$name")
        if (fallback.exists()) fallback.delete()
    }

    fun exists(name: String): Boolean {
        val appContext = appContextRef ?: return false
        if (queryUriByName(name) != null) return true
        return File(appContext.filesDir, "videos/$name").exists()
    }

    /** Read stream for a stored clip (MediaStore or app-private fallback). */
    fun openStream(name: String): java.io.InputStream? {
        val appContext = appContextRef ?: return null
        val uri = queryUriByName(name)
        if (uri != null) {
            try {
                return appContext.contentResolver.openInputStream(uri)
            } catch (_: Exception) {
            }
        }
        val fallback = File(appContext.filesDir, "videos/$name")
        return if (fallback.exists()) fallback.inputStream() else null
    }

    /** Whether the stored clip carries an audio track. Pure read, no FGS. */
    fun hasAudio(name: String): Boolean {
        val appContext = appContextRef ?: return false
        val uri = queryUriByName(name)
        val fallback = File(appContext.filesDir, "videos/$name")
        return try {
            val extractor = MediaExtractor()
            try {
                if (uri != null) {
                    extractor.setDataSource(appContext, uri, null)
                } else if (fallback.exists()) {
                    extractor.setDataSource(fallback.path)
                } else {
                    return false
                }
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                        return true
                    }
                }
                false
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "hasAudio failed for $name", e)
            false
        }
    }

    /**
     * Dimensions of the stored clip (width x height), or null when the clip is
     * missing or its headers can't be read. Pure read — works without the FGS.
     */
    fun videoInfo(name: String): Map<String, Int>? {
        val appContext = appContextRef ?: return null
        val uri = queryUriByName(name)
        val fallback = File(appContext.filesDir, "videos/$name")
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri != null) {
                retriever.setDataSource(appContext, uri)
            } else if (fallback.exists()) {
                retriever.setDataSource(fallback.path)
            } else {
                return null
            }
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: return null
            mapOf("width" to width, "height" to height)
        } catch (e: Exception) {
            Log.w(TAG, "videoInfo failed for $name", e)
            null
        } finally {
            retriever.release()
        }
    }

    /** Returns an error message on failure, or null when the player opened. */
    fun open(name: String): String? {
        val appContext = appContextRef ?: return "no application context"
        val uri = queryUriByName(name)
        val fallback = File(appContext.filesDir, "videos/$name")
        val contentUri = if (uri != null) {
            uri
        } else if (fallback.exists()) {
            FileProvider.getUriForFile(
                appContext, "${appContext.packageName}.fileprovider", fallback
            )
        } else {
            Log.w(TAG, "open video: no such clip $name")
            return "no such clip: $name"
        }
        // Launched from the application context, so NEW_TASK is mandatory.
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            null
        } catch (e: Exception) {
            Log.w(TAG, "open video failed for $name", e)
            "no app can play this clip: ${e.message}"
        }
    }
}
