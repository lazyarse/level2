package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.ChannelSettings
import io.securitycam.level2.core.Snapshot
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

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
) : io.securitycam.level2.core.Channel {

    private val client: OkHttpClient =
        client ?: OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    override val type: String get() = "telegram"

    private fun endpoint(method: String): String =
        "https://api.telegram.org/bot${settings.botToken}/$method"

    override suspend fun send(message: AlertMessage) {
        val photo = message.snapshot
        if (photo != null) {
            if (!sendPhoto(photo, message.text)) {
                sendMessage(message.text)
            }
        } else {
            sendMessage(message.text)
        }
    }

    private suspend fun sendPhoto(photo: Snapshot, caption: String): Boolean {
        val tmp = File.createTempFile("level2", ".img")
        try {
            tmp.writeBytes(photo.bytes)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", settings.chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart(
                    "photo",
                    photo.name,
                    tmp.asRequestBody(photo.mimeType.toMediaType()),
                )
                .build()
            val response = client.newCall(Request.Builder().url(endpoint("sendPhoto")).post(body).build()).execute()
            response.use { return isOk(it.body?.string()) }
        } finally {
            tmp.delete()
        }
    }

    private suspend fun sendMessage(text: String) {
        val body = jsonEncode("chat_id" to settings.chatId, "text" to text)
            .toRequestBody("application/json".toMediaType())
        val response = client.newCall(Request.Builder().url(endpoint("sendMessage")).post(body).build()).execute()
        response.use {
            if (!isOk(it.body?.string())) {
                error("Telegram sendMessage failed (${it.code})")
            }
        }
    }

    private fun isOk(body: String?): Boolean {
        if (body == null) return false
        // Telegram replies {"ok":true,...}; a targeted match avoids pulling a
        // JSON parser into the unit-test classpath.
        return OK_REGEX.containsMatchIn(body)
    }

    override suspend fun sendTest() {
        sendMessage("Security Cam: test alert")
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
    }
}