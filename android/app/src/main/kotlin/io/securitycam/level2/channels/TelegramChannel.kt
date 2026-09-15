package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.ChannelSettings
import io.securitycam.level2.core.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class TelegramChannelSettings(
    val botToken: String = "",
    val chatId: String = "",
) : ChannelSettings() {
    override val type: String get() = "telegram"
    override fun toJson(): Map<String, Any?> = mapOf("botToken" to botToken, "chatId" to chatId)
    override val secretFields: List<String> get() = listOf("botToken")

    companion object {
        fun fromJson(json: Map<String, Any?>): TelegramChannelSettings = TelegramChannelSettings(
            botToken = json["botToken"] as? String ?: "",
            chatId = json["chatId"] as? String ?: "",
        )
    }
}

/** Sends alerts to the Telegram Bot API (sendPhoto with sendMessage fallback). */
class TelegramChannel(
    override val id: String,
    override val enabled: Boolean = true,
    override val settings: TelegramChannelSettings,
    client: OkHttpClient? = null,
    /** Builds the photo for test sends; injectable so JVM tests avoid Bitmap. */
    private val testSnapshot: () -> Snapshot = ::sampleTestSnapshot,
) : io.securitycam.level2.core.Channel {

    private val client: OkHttpClient = client ?: newHttpClient()

    override val type: String get() = "telegram"

    private fun endpoint(method: String): String =
        "https://api.telegram.org/bot${settings.botToken}/$method"

    override suspend fun send(message: AlertMessage) {
        val preview = message.videoPreview
        if (preview != null) {
            sendAnimation(preview, message.text)
            return
        }
        val photo = message.snapshot
        if (photo != null) {
            if (!sendPhoto(photo, message.text)) {
                sendMessage(message.text)
            }
        } else {
            sendMessage(message.text)
        }
    }

    private suspend fun sendAnimation(gif: Snapshot, caption: String) {
        withTempSnapshot(gif) { tmp ->
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", settings.chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart(
                    "animation",
                    gif.name,
                    tmp.asRequestBody(safeMediaType(gif.mimeType)),
                )
                .build()
            val response = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(endpoint("sendAnimation")).post(body).build()).execute()
            }
            response.use {
                val respBody = it.body?.string()
                // A rejected preview must surface so the caller (outbox) can
                // retry the GIF delivery rather than silently degrade.
                if (!isOk(respBody)) {
                    error("Telegram sendAnimation failed (${it.code}): ${telegramError(respBody)}")
                }
            }
        }
    }

    private suspend fun sendPhoto(photo: Snapshot, caption: String): Boolean =
        withTempSnapshot(photo) { tmp ->
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", settings.chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart(
                    "photo",
                    photo.name,
                    tmp.asRequestBody(safeMediaType(photo.mimeType)),
                )
                .build()
            val response = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(endpoint("sendPhoto")).post(body).build()).execute()
            }
            response.use { return@withTempSnapshot isOk(it.body?.string()) }
        }

    private suspend fun sendMessage(text: String) {
        val body = jsonEncode("chat_id" to settings.chatId, "text" to text)
            .toRequestBody("application/json".toMediaType())
        val response = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(endpoint("sendMessage")).post(body).build()).execute()
        }
        response.use {
            val respBody = it.body?.string()
            if (!isOk(respBody)) {
                error("Telegram sendMessage failed (${it.code}): ${telegramError(respBody)}")
            }
        }
    }

    private fun isOk(body: String?): Boolean {
        if (body == null) return false
        // Telegram replies {"ok":true,...}; a targeted match avoids pulling a
        // JSON parser into the unit-test classpath.
        return OK_REGEX.containsMatchIn(body)
    }

    /** Surfaces Telegram's human-readable reason (e.g. "bot can't initiate
     *  conversation with a user") instead of a bare error code. */
    private fun telegramError(body: String?): String {
        if (body == null) return "no response body"
        val m = DESCRIPTION_REGEX.find(body)
        return m?.groupValues?.get(1) ?: body.take(200)
    }

    override suspend fun sendTest() {
        // Route through send() so the test exercises the photo path; a
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
        if (settings.botToken.isEmpty() || settings.chatId.isEmpty()) {
            return "Bot token and chat ID are required"
        }
        if (!TOKEN_REGEX.matches(settings.botToken)) {
            return "Bot token is not in the expected format"
        }
        return null
    }

    companion object {
        // \d+:[A-Za-z0-9_-]+
        private val TOKEN_REGEX = Regex("^\\d+:[A-Za-z0-9_-]+$")
        private val OK_REGEX = Regex("\"ok\"\\s*:\\s*true")
        private val DESCRIPTION_REGEX = Regex("\"description\"\\s*:\\s*\"([^\"]*)\"")
    }
}