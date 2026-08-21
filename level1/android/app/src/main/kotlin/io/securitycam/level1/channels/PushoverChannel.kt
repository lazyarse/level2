package io.securitycam.level1.channels

import io.securitycam.level1.core.AlertMessage
import io.securitycam.level1.core.ChannelSettings
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class PushoverChannelSettings(
    val appToken: String = "",
    val userKey: String = "",
    val sound: String = "",
    val priority: Int = 0,
) : ChannelSettings() {
    override val type: String get() = "pushover"
    override fun toJson(): Map<String, Any?> = mapOf(
        "appToken" to appToken,
        "userKey" to userKey,
        "sound" to sound,
        "priority" to priority,
    )
    override val secretFields: List<String> get() = listOf("appToken", "userKey")

    companion object {
        fun fromJson(json: Map<String, Any?>): PushoverChannelSettings = PushoverChannelSettings(
            appToken = json["appToken"] as? String ?: "",
            userKey = json["userKey"] as? String ?: "",
            sound = json["sound"] as? String ?: "",
            priority = (json["priority"] as? Number)?.toInt() ?: 0,
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
) : io.securitycam.level1.core.Channel {

    private val client: OkHttpClient =
        client ?: OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    override val type: String get() = "pushover"

    private fun fields(message: String): List<Pair<String, String>> = buildList {
        add("token" to settings.appToken)
        add("user" to settings.userKey)
        add("message" to message)
        if (settings.sound.isNotEmpty()) add("sound" to settings.sound)
        add("priority" to settings.priority.toString())
    }

    override suspend fun send(message: AlertMessage) {
        val snapshot = message.snapshot
        if (snapshot != null) {
            val tmp = File.createTempFile("level1", ".img")
            try {
                tmp.writeBytes(snapshot.bytes)
                val builder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                for ((k, v) in fields(message.text)) builder.addFormDataPart(k, v)
                builder.addFormDataPart(
                    "attachment",
                    snapshot.name,
                    tmp.asRequestBody(snapshot.mimeType.toMediaType()),
                )
                val response = client.newCall(Request.Builder().url(ENDPOINT).post(builder.build()).build()).execute()
                response.use { check(it.isSuccessful) { "Pushover failed (${it.code}) ${it.body?.string()}" } }
            } finally {
                tmp.delete()
            }
        } else {
            val body = FormBody.Builder().apply {
                for ((k, v) in fields(message.text)) add(k, v)
            }.build()
            val response = client.newCall(Request.Builder().url(ENDPOINT).post(body).build()).execute()
            response.use { check(it.isSuccessful) { "Pushover failed (${it.code}) ${it.body?.string()}" } }
        }
    }

    override suspend fun sendTest() {
        send(
            AlertMessage(
                timestamp = java.time.Instant.now(),
                triggerType = "test",
                text = "Security Cam: test alert",
            ),
        )
    }

    override fun validate(): String? {
        if (settings.appToken.isEmpty()) return "App token is required"
        if (settings.userKey.isEmpty()) return "User key is required"
        return null
    }

    companion object {
        private const val ENDPOINT = "https://api.pushover.net/1/messages.json"
    }
}