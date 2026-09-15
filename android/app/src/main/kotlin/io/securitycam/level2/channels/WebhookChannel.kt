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
    /** Builds the photo for test sends (discord preset); injectable so JVM tests avoid Bitmap. */
    private val testSnapshot: () -> Snapshot = ::sampleTestSnapshot,
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
        val text = fitWebhookText(message.text)
        // A preview push carries only the GIF (no snapshot, and vice versa),
        // so a single attachment covers both paths.
        val attach = message.snapshot ?: message.videoPreview
        if (attach == null) {
            sendJson("content" to text)
            return
        }
        withTempSnapshot(attach) { tmp ->
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("content", text)
                .addFormDataPart(
                    "file",
                    safeAttachmentName(attach.name),
                    tmp.asRequestBody(safeMediaType(attach.mimeType)),
                )
                .build()
            val response = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(endpoint).post(body).build()).execute()
            }
            response.use {
                if (!it.isSuccessful && it.code in 400..499) {
                    // The server rejected the upload itself (bad webhook,
                    // rejected file): degrade to text so the alert still
                    // arrives. 5xx errors keep the photo for the outbox
                    // retry path (mirrors PushoverChannel).
                    sendJson("content" to text)
                } else {
                    check(it.isSuccessful) { "Webhook failed (${it.code}) ${it.body?.string()?.take(200)}" }
                }
            }
        }
    }

    private suspend fun sendNtfy(message: AlertMessage) {
        val text = fitWebhookText(message.text)
        val preview = message.videoPreview
        if (preview != null) {
            // ntfy accepts attachments as a multipart "file" part (same shape
            // as Discord); keep the auth/title headers on the multipart POST.
            withTempSnapshot(preview) { tmp ->
                val builder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("message", text)
                    .addFormDataPart(
                        "file",
                        safeAttachmentName(preview.name),
                        tmp.asRequestBody(safeMediaType(preview.mimeType)),
                    )
                val requestBuilder = Request.Builder().url(endpoint).post(builder.build())
                if (settings.bearerToken.isNotEmpty()) {
                    requestBuilder.header("Authorization", "Bearer ${settings.bearerToken}")
                }
                if (settings.title.isNotEmpty()) requestBuilder.header("X-Title", settings.title)
                val response = withContext(Dispatchers.IO) {
                    client.newCall(requestBuilder.build()).execute()
                }
                response.use {
                    check(it.isSuccessful) { "Webhook failed (${it.code}) ${it.body?.string()?.take(200)}" }
                }
            }
            return
        }
        val headers = mutableMapOf("content-type" to "text/plain")
        if (settings.bearerToken.isNotEmpty()) headers["Authorization"] = "Bearer ${settings.bearerToken}"
        if (settings.title.isNotEmpty()) headers["X-Title"] = settings.title
        post(headers, text)
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
        // Route through send() so the test exercises the attachment path on
        // presets that support it (discord); a snapshot failure degrades to
        // text-only instead of failing the test.
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
        if (settings.preset !in webhookPresets) return "Unknown webhook preset"
        val url = settings.url.trim()
        if (url.isEmpty()) return "Webhook URL is required"
        if (!url.startsWith("https://")) return "Webhook URL must be https"
        if (settings.preset == "custom" && settings.bodyStyle != "json" && settings.bodyStyle != "text") {
            return "Body style must be json or text"
        }
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
        /** Hardening cap on outbound text (Discord rejects content past 2000 chars). */
        const val MAX_TEXT_CHARS = 2000

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

/**
 * Fits alert text to the webhook cap: overlong text is rejected with an API
 * error (which would retry forever), so ellipsize up-front.
 */
internal fun fitWebhookText(text: String): String =
    if (text.length <= WebhookChannel.MAX_TEXT_CHARS) text
    else text.take(WebhookChannel.MAX_TEXT_CHARS - 1) + "…"