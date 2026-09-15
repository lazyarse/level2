package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.ChannelSettings
import io.securitycam.level2.core.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

class PushoverChannelSettings(
    val appToken: String = "",
    val userKey: String = "",
    val sound: String = "",
    val priority: Int = 0,
    /** Emergency (priority 2) re-alert interval, seconds (API requires >= 30). */
    val retrySeconds: Int = 60,
    /** Emergency (priority 2) total re-alert window, seconds (API requires <= 10800). */
    val expireSeconds: Int = 3600,
) : ChannelSettings() {
    override val type: String get() = "pushover"
    override fun toJson(): Map<String, Any?> = mapOf(
        "appToken" to appToken,
        "userKey" to userKey,
        "sound" to sound,
        "priority" to priority,
        "retrySeconds" to retrySeconds,
        "expireSeconds" to expireSeconds,
    )
    override val secretFields: List<String> get() = listOf("appToken", "userKey")

    companion object {
        fun fromJson(json: Map<String, Any?>): PushoverChannelSettings = PushoverChannelSettings(
            appToken = json["appToken"] as? String ?: "",
            userKey = json["userKey"] as? String ?: "",
            sound = json["sound"] as? String ?: "",
            priority = (json["priority"] as? Number)?.toInt() ?: 0,
            retrySeconds = (json["retrySeconds"] as? Number)?.toInt() ?: 60,
            expireSeconds = (json["expireSeconds"] as? Number)?.toInt() ?: 3600,
        )
    }
}

/**
 * Sends alerts to Pushover via the messages.json endpoint. The app token and
 * user key are secrets carried in the request body/fields.
 */
class PushoverChannel(
    override val id: String,
    override val enabled: Boolean = true,
    override val settings: PushoverChannelSettings,
    client: OkHttpClient? = null,
    /** Fits snapshots to the attachment cap; injectable so JVM tests avoid Bitmap. */
    private val fitSnapshot: (Snapshot, Int) -> Snapshot? = ::fitSnapshotForUpload,
    /** Builds the attachment for test sends; injectable so JVM tests avoid Bitmap. */
    private val testSnapshot: () -> Snapshot = ::sampleTestSnapshot,
) : io.securitycam.level2.core.Channel {

    private val client: OkHttpClient = client ?: newHttpClient()

    override val type: String get() = "pushover"

    private fun fields(message: String): List<Pair<String, String>> = buildList {
        add("token" to settings.appToken)
        add("user" to settings.userKey)
        add("message" to fitMessage(message))
        if (settings.sound.isNotEmpty()) add("sound" to settings.sound)
        add("priority" to settings.priority.toString())
        if (settings.priority == 2) {
            add("retry" to settings.retrySeconds.toString())
            add("expire" to settings.expireSeconds.toString())
        }
    }

    override suspend fun send(message: AlertMessage) {
        val text = fitMessage(message.text)
        // Preview pushes carry only the GIF (no snapshot): attach it directly.
        message.videoPreview?.let { preview ->
            sendMultipart(text, preview)
            return
        }
        // Oversized originals are downscaled to fit; unsalvageable ones fall
        // back to the text form (mirrors Telegram's photo→message fallback).
        val upload = message.snapshot?.let { fitSnapshot(it, MAX_ATTACHMENT_BYTES) }
        if (message.snapshot != null && upload != null) {
            sendMultipart(text, upload)
        } else {
            postText(text)
        }
    }

    private suspend fun sendMultipart(text: String, attach: Snapshot) {
        withTempSnapshot(attach) { tmp ->
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
            for ((k, v) in fields(text)) builder.addFormDataPart(k, v)
            builder.addFormDataPart(
                "attachment",
                attach.name,
                tmp.asRequestBody(safeMediaType(attach.mimeType)),
            )
            val response = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(ENDPOINT).post(builder.build()).build()).execute()
            }
            response.use {
                if (!it.isSuccessful && it.code in 400..499) {
                    // The server rejected the upload itself (bad key,
                    // oversize attachment): degrade to text so the alert
                    // still arrives. 5xx/network errors keep the photo
                    // for the outbox retry path.
                    postText(text)
                } else {
                    check(it.isSuccessful) { "Pushover failed (${it.code}) ${it.body?.string()?.take(200)}" }
                }
            }
        }
    }

    private suspend fun postText(text: String) {
        val body = FormBody.Builder().apply {
            for ((k, v) in fields(text)) add(k, v)
        }.build()
        val response = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(ENDPOINT).post(body).build()).execute()
        }
        response.use {
            check(it.isSuccessful) { "Pushover failed (${it.code}) ${it.body?.string()?.take(200)}" }
        }
    }

    override suspend fun sendTest() {
        // Route through send() so the test exercises the attachment path; a
        // snapshot failure degrades to text-only instead of failing the test.
        val snap = runCatching(testSnapshot).getOrNull()
        send(
            AlertMessage(
                timestamp = java.time.Instant.now(),
                triggerType = "test",
                text = "Security Cam: test alert",
                snapshot = snap,
            ),
        )
    }

    override fun validate(): String? {
        if (settings.appToken.isEmpty()) return "App token is required"
        if (settings.userKey.isEmpty()) return "User key is required"
        if (settings.priority !in -2..2) return "Priority must be between -2 and 2"
        if (settings.sound.isNotEmpty() && settings.sound !in VALID_SOUNDS) {
            return "Unknown notification sound"
        }
        if (settings.priority == 2) {
            if (settings.retrySeconds < 30) return "Emergency retry must be at least 30 seconds"
            if (settings.expireSeconds > 10800) return "Emergency expiry must be at most 10800 seconds"
        }
        return null
    }

    companion object {
        private const val ENDPOINT = "https://api.pushover.net/1/messages.json"

        /** Server-side cap: larger attachments are rejected with an API error. */
        const val MAX_ATTACHMENT_BYTES = 5_242_880

        /** Server-side cap on the message field: longer text is rejected. */
        const val MAX_MESSAGE_CHARS = 1024

        /** Documented sound names (Pushover API); blank means the default sound. */
        internal val VALID_SOUNDS = setOf(
            "pushover", "bike", "bugle", "cashregister", "classical", "cosmic",
            "falling", "gamelan", "incoming", "intermission", "magic",
            "mechanical", "pianobar", "siren", "spacealarm", "tugboat",
            "alien", "climb", "persistent", "echo", "updown", "vibrate", "none",
        )
    }
}

/**
 * Fits alert text to Pushover's message cap: overlong text is rejected with
 * an API error (which would retry forever), so ellipsize up-front.
 */
internal fun fitMessage(text: String): String =
    if (text.length <= PushoverChannel.MAX_MESSAGE_CHARS) text
    else text.take(PushoverChannel.MAX_MESSAGE_CHARS - 1) + "…"