package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.ChannelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

val webhookPresets = listOf("discord", "ntfy", "slack", "teams", "custom")

class WebhookChannelSettings(
    val preset: String = "custom",
    val url: String = "",
    val bearerToken: String = "",
    val title: String = "",
    val bodyStyle: String = "json",
) : ChannelSettings() {
    override val type: String get() = "webhook"
    override fun toJson(): Map<String, Any?> = mapOf(
        "preset" to preset,
        "url" to url,
        "bearerToken" to bearerToken,
        "title" to title,
        "bodyStyle" to bodyStyle,
    )
    override val secretFields: List<String> get() = listOf("url", "bearerToken")

    companion object {
        fun fromJson(json: Map<String, Any?>): WebhookChannelSettings = WebhookChannelSettings(
            preset = json["preset"] as? String ?: "custom",
            url = json["url"] as? String ?: "",
            bearerToken = json["bearerToken"] as? String ?: "",
            title = json["title"] as? String ?: "",
            bodyStyle = json["bodyStyle"] as? String ?: "json",
        )
    }
}

/**
 * Sends alerts to a generic webhook URL. The [WebhookChannelSettings.preset]
 * selects the request shape (discord multipart/JSON, ntfy text/plain,
 * slack/teams JSON, custom JSON or text). The webhook URL and bearer token
 * carry the auth secrets.
 */
class WebhookChannel(
    override val id: String,
    override val enabled: Boolean = true,
    override val settings: WebhookChannelSettings,
    client: OkHttpClient? = null,
) : io.securitycam.level2.core.Channel {

    private val client: OkHttpClient = client ?: newHttpClient()

    override val type: String get() = "webhook"

    /** Trimmed endpoint: validation trims, so sends must too (trailing spaces 404). */
    private val endpoint: String get() = settings.url.trim()

    override suspend fun send(message: AlertMessage) {
        when (settings.preset) {
            "discord" -> sendDiscord(message)
            "ntfy" -> sendNtfy(message)
            "slack", "teams" -> sendJson("text" to message.text)
            else -> sendCustom(message)
        }
    }

    private suspend fun sendDiscord(message: AlertMessage) {
        val snapshot = message.snapshot ?: run {
            sendJson("content" to message.text)
            return
        }
        withTempSnapshot(snapshot) { tmp ->
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("content", message.text)
                .addFormDataPart(
                    "file",
                    snapshot.name,
                    tmp.asRequestBody(safeMediaType(snapshot.mimeType)),
                )
                .build()
            val response = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(endpoint).post(body).build()).execute()
            }
            response.use {
                if (!it.isSuccessful) sendJson("content" to message.text)
            }
        }
    }

    private suspend fun sendNtfy(message: AlertMessage) {
        val headers = mutableMapOf("content-type" to "text/plain")
        if (settings.bearerToken.isNotEmpty()) headers["Authorization"] = "Bearer ${settings.bearerToken}"
        if (settings.title.isNotEmpty()) headers["X-Title"] = settings.title
        post(headers, message.text)
    }

    private suspend fun sendCustom(message: AlertMessage) {
        val headers = mutableMapOf<String, String>()
        if (settings.bearerToken.isNotEmpty()) headers["Authorization"] = "Bearer ${settings.bearerToken}"
        if (settings.bodyStyle == "text") {
            headers["content-type"] = "text/plain"
            post(headers, message.text)
        } else {
            sendJson("text" to message.text)
        }
    }

    private suspend fun sendJson(vararg fields: Pair<String, Any?>) {
        post(mapOf("content-type" to "application/json"), jsonEncode(*fields))
    }

    private suspend fun post(headers: Map<String, String>, body: String) {
        val builder = Request.Builder().url(endpoint).post(body.toRequestBody())
        for ((k, v) in headers) builder.header(k, v)
        val response = withContext(Dispatchers.IO) {
            client.newCall(builder.build()).execute()
        }
        response.use {
            check(it.isSuccessful) { "Webhook failed (${it.code}) ${it.body?.string()?.take(200)}" }
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
        val url = settings.url.trim()
        if (url.isEmpty()) return "Webhook URL is required"
        if (!url.startsWith("https://")) return "Webhook URL must be https"
        when (settings.preset) {
            "discord" -> if (!DISCORD_REGEX.matches(url)) {
                return "Webhook URL is not a valid Discord webhook URL"
            }
            "slack" -> if (!SLACK_REGEX.matches(url)) {
                return "Webhook URL is not a valid Slack incoming webhook URL"
            }
            "teams" -> if (!TEAMS_REGEX.matches(url)) {
                return "Webhook URL is not a valid Teams webhook URL"
            }
            "ntfy" -> {
                val topic = url.substring("https://".length).substringAfter('/', "")
                if (topic.isEmpty()) return "ntfy topic is missing from the URL"
            }
        }
        return null
    }

    companion object {
        // ^https://(?:canary|ptb\.)?discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_-]+(\?.*)?$
        private val DISCORD_REGEX =
            Regex("^https://(?:canary|ptb\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[A-Za-z0-9_-]+(\\?[A-Za-z0-9_=&%\\-.]*)?\$")
        // ^https://hooks\.slack\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/[A-Za-z0-9_-]+$
        private val SLACK_REGEX =
            Regex("^https://hooks\\.slack\\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/[A-Za-z0-9_-]+\$")
        // Legacy ^https://<sub>.webhook.office.com/webhookbot/.+$ plus the new
        // Workflows ^https://<env>.logic.azure.com(:port)/.+$
        private val TEAMS_REGEX =
            Regex("^https://([A-Za-z0-9.\\-]+\\.webhook\\.office\\.com/webhookbot/.+|[A-Za-z0-9.\\-]+\\.logic\\.azure\\.com(:\\d+)?/.+)\$")
    }
}