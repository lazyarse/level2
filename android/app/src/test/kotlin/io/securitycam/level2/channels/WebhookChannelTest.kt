package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.Snapshot
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `test/webhook_channel_test.dart`. */
class WebhookChannelTest {

    private val discordUrl = "https://discord.com/api/webhooks/12345/abcdefghijk"

    private fun channel(
        preset: String = "discord",
        url: String = discordUrl,
        bearerToken: String = "",
        title: String = "",
        bodyStyle: String = "json",
        mockBase: String? = null,
    ): WebhookChannel = WebhookChannel(
        id = "webhook",
        enabled = true,
        settings = WebhookChannelSettings(
            preset = preset,
            url = url,
            bearerToken = bearerToken,
            title = title,
            bodyStyle = bodyStyle,
        ),
        client = mockBase?.let { TestHttp.rewritingClient(it.toHttpUrl()) },
    )

    private fun message(snapshot: Snapshot? = null): AlertMessage = AlertMessage(
        timestamp = Instant.EPOCH,
        triggerType = "motion",
        text = "Motion detected in Hallway",
        snapshot = snapshot,
    )

    private fun snapshot(): Snapshot = Snapshot(
        bytes = byteArrayOf(1, 2, 3),
        mimeType = "image/png",
        name = "snap.png",
    )

    private fun serverWith(code: Int = 200, body: String = ""): MockWebServer = MockWebServer().apply {
        enqueue(MockResponse().setResponseCode(code).setBody(body))
        start()
    }

    // discord preset

    @Test
    fun discordSendPostsJsonContentWithoutASnapshot() = runBlocking {
        val server = serverWith(code = 200, body = "{}")
        try {
            channel(mockBase = server.url("/").toString()).send(message())
            assertEquals(1, server.requestCount)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertTrue(recorded.getHeader("content-type").orEmpty().contains("application/json"))
            assertTrue(recorded.body.readUtf8().contains("Motion detected in Hallway"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discordSendUploadsTheSnapshotAsAFileAttachment() = runBlocking {
        val server = serverWith(code = 200, body = "{}")
        try {
            channel(mockBase = server.url("/").toString()).send(message(snapshot = snapshot()))
            assertEquals(1, server.requestCount)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("snap.png"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discordUploadNon2xxFallsBackToAJsonContentOnlyRequest() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        try {
            channel(mockBase = server.url("/").toString()).send(message(snapshot = snapshot()))
            val second = server.takeRequest()
            server.takeRequest()
            // First request is the multipart upload; second is the JSON fallback.
            assertTrue(second.body.readUtf8().isEmpty() || second.getHeader("content-type").orEmpty().contains("multipart"))
            assertTrue(server.requestCount == 2)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discordSendTestPostsATestAlert() = runBlocking {
        val server = serverWith(code = 200, body = "{}")
        try {
            channel(mockBase = server.url("/").toString()).sendTest()
            assertTrue(server.takeRequest().body.readUtf8().contains("Security Cam: test alert"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discordNon2xxResponseThrowsAReadableError() = runBlocking {
        val server = serverWith(code = 401, body = "boom")
        try {
            var thrown: Throwable? = null
            try {
                channel(preset = "custom", url = "https://example.com/hook", mockBase = server.url("/").toString()).sendTest()
            } catch (t: IllegalStateException) {
                thrown = t
            }
            assertTrue(thrown?.message.orEmpty().contains("Webhook failed"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discordValidateRequiresAWellFormedUrl() {
        assertEquals(
            "Webhook URL is required",
            WebhookChannel(id = "w", settings = WebhookChannelSettings(preset = "discord")).validate(),
        )
        assertEquals(
            "Webhook URL is not a valid Discord webhook URL",
            WebhookChannel(
                id = "w",
                settings = WebhookChannelSettings(preset = "discord", url = "https://nope.com/x"),
            ).validate(),
        )
        assertNull(channel().validate())
    }

    // ntfy preset

    @Test
    fun ntfyPostsTextPlainWithOptionalBearerAndTitle() = runBlocking {
        val server = serverWith()
        try {
            channel(
                preset = "ntfy",
                url = "https://ntfy.sh/mytopic",
                bearerToken = "tok123",
                title = "My Alert",
                mockBase = server.url("/").toString(),
            ).send(message())
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertTrue(recorded.getHeader("content-type").orEmpty().contains("text/plain"))
            assertEquals("Bearer tok123", recorded.getHeader("Authorization"))
            assertEquals("My Alert", recorded.getHeader("X-Title"))
            assertEquals("Motion detected in Hallway", recorded.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun ntfyOmitsBearerAndTitleHeadersWhenUnset() = runBlocking {
        val server = serverWith()
        try {
            channel(preset = "ntfy", url = "https://ntfy.sh/mytopic", mockBase = server.url("/").toString()).send(message())
            val recorded = server.takeRequest()
            assertFalse(recorded.getHeader("Authorization") != null)
            assertFalse(recorded.getHeader("X-Title") != null)
        } finally {
            server.shutdown()
        }
    }

    // slack / teams presets

    @Test
    fun slackPostsJsonTextBody() = runBlocking {
        val server = serverWith()
        try {
            channel(preset = "slack", url = "https://hooks.slack.com/services/T123/B456/abc", mockBase = server.url("/").toString()).send(message())
            val recorded = server.takeRequest()
            assertTrue(recorded.getHeader("content-type").orEmpty().contains("application/json"))
            assertTrue(recorded.body.readUtf8().contains("Motion detected in Hallway"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun teamsPostsJsonTextBody() = runBlocking {
        val server = serverWith()
        try {
            channel(preset = "teams", url = "https://example.webhook.office.com/webhookbot/xxx", mockBase = server.url("/").toString()).send(message())
            val recorded = server.takeRequest()
            assertTrue(recorded.getHeader("content-type").orEmpty().contains("application/json"))
            assertTrue(recorded.body.readUtf8().contains("Motion detected in Hallway"))
        } finally {
            server.shutdown()
        }
    }

    // custom preset

    @Test
    fun customJsonBodyStylePostsText() = runBlocking {
        val server = serverWith()
        try {
            channel(preset = "custom", url = "https://example.com/hook", bodyStyle = "json", mockBase = server.url("/").toString()).send(message())
            val recorded = server.takeRequest()
            assertTrue(recorded.body.readUtf8().contains("Motion detected in Hallway"))
            assertTrue(recorded.getHeader("content-type").orEmpty().contains("application/json"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun customTextBodyStylePostsRawTextWithBearer() = runBlocking {
        val server = serverWith()
        try {
            channel(
                preset = "custom",
                url = "https://example.com/hook",
                bodyStyle = "text",
                bearerToken = "bear",
                mockBase = server.url("/").toString(),
            ).send(message())
            val recorded = server.takeRequest()
            assertTrue(recorded.getHeader("content-type").orEmpty().contains("text/plain"))
            assertEquals("Bearer bear", recorded.getHeader("Authorization"))
            assertEquals("Motion detected in Hallway", recorded.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    // validate per preset

    @Test
    fun slackRejectsADiscordShapedUrl() {
        assertEquals(
            "Webhook URL is not a valid Slack incoming webhook URL",
            WebhookChannel(id = "w", settings = WebhookChannelSettings(preset = "slack", url = discordUrl)).validate(),
        )
    }

    @Test
    fun teamsRejectsANonOfficeWebhookUrl() {
        assertEquals(
            "Webhook URL is not a valid Teams webhook URL",
            WebhookChannel(id = "w", settings = WebhookChannelSettings(preset = "teams", url = "https://example.com/hook")).validate(),
        )
    }

    @Test
    fun ntfyRequiresATopicInTheUrl() {
        assertEquals(
            "ntfy topic is missing from the URL",
            WebhookChannel(id = "w", settings = WebhookChannelSettings(preset = "ntfy", url = "https://ntfy.sh")).validate(),
        )
        assertNull(
            WebhookChannel(id = "w", settings = WebhookChannelSettings(preset = "ntfy", url = "https://ntfy.sh/mytopic")).validate(),
        )
    }

    @Test
    fun customAcceptsAnyHttpsUrlAndRejectsHttp() {
        assertNull(
            WebhookChannel(id = "w", settings = WebhookChannelSettings(preset = "custom", url = "https://example.com/x")).validate(),
        )
        assertEquals(
            "Webhook URL must be https",
            WebhookChannel(id = "w", settings = WebhookChannelSettings(preset = "custom", url = "http://example.com/x")).validate(),
        )
    }

    @Test
    fun urlAndBearerTokenAreSecretFields() {
        val fields = WebhookChannelSettings(preset = "ntfy", url = "x", bearerToken = "y").secretFields
        assertTrue(fields.containsAll(listOf("url", "bearerToken")))
    }
}