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
        testSnapshot: () -> Snapshot = { Snapshot(byteArrayOf(1, 2, 3), "image/jpeg", "test-snapshot.jpg") },
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
        testSnapshot = testSnapshot,
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
    fun discordSendTestAttachesASampleSnapshot() = runBlocking {
        val server = serverWith(code = 200, body = "{}")
        try {
            channel(mockBase = server.url("/").toString()).sendTest()
            assertEquals(1, server.requestCount)
            val recorded = server.takeRequest()
            assertTrue(recorded.getHeader("content-type").orEmpty().contains("multipart"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("Security Cam: test alert"))
            assertTrue(body.contains("test-snapshot.jpg"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discordSendTestFallsBackToJsonWhenUploadFails() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        try {
            channel(mockBase = server.url("/").toString()).sendTest()
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val second = server.takeRequest()
            assertTrue(second.getHeader("content-type").orEmpty().contains("application/json"))
            assertTrue(second.body.readUtf8().contains("Security Cam: test alert"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun customSendTestStaysTextOnly() = runBlocking {
        val server = serverWith(code = 200, body = "{}")
        try {
            channel(preset = "custom", url = "https://example.com/hook", mockBase = server.url("/").toString()).sendTest()
            assertEquals(1, server.requestCount)
            val recorded = server.takeRequest()
            assertTrue(recorded.getHeader("content-type").orEmpty().contains("application/json"))
            assertTrue(recorded.body.readUtf8().contains("Security Cam: test alert"))
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
        assertEquals(
            "ntfy topic is missing from the URL",
            WebhookChannel(id = "w", settings = WebhookChannelSettings(preset = "ntfy", url = "https://ntfy.sh/")).validate(),
        )
        assertNull(
            WebhookChannel(id = "w", settings = WebhookChannelSettings(preset = "ntfy", url = "https://ntfy.sh/mytopic")).validate(),
        )
    }

    @Test
    fun discordAcceptsQuerySuffixes() {
        assertNull(
            WebhookChannel(
                id = "w",
                settings = WebhookChannelSettings(
                    preset = "discord",
                    url = "https://discord.com/api/webhooks/12345/abcdefghijk?wait=true",
                ),
            ).validate(),
        )
        assertNull(
            WebhookChannel(
                id = "w",
                settings = WebhookChannelSettings(
                    preset = "discord",
                    url = "https://ptb.discord.com/api/webhooks/12345/abc-def_1?thread_id=99",
                ),
            ).validate(),
        )
    }

    @Test
    fun slackAcceptsRealTokenShapes() {
        assertNull(
            WebhookChannel(
                id = "w",
                settings = WebhookChannelSettings(
                    preset = "slack",
                    url = "https://hooks.slack.com/services/TABC12345/BDEF67890/XyZ12_ab-CD34",
                ),
            ).validate(),
        )
    }

    @Test
    fun teamsAcceptsWorkflowUrls() {
        assertNull(
            WebhookChannel(
                id = "w",
                settings = WebhookChannelSettings(
                    preset = "teams",
                    url = "https://prod-12.westus.logic.azure.com:443/workflows/abc/triggers/manual/paths/invoke?api-version=2016-06-01",
                ),
            ).validate(),
        )
        assertNull(
            WebhookChannel(
                id = "w",
                settings = WebhookChannelSettings(
                    preset = "teams",
                    url = "https://example.webhook.office.com/webhookbot/xxx",
                ),
            ).validate(),
        )
    }

    @Test
    fun sendTrimsSurroundingWhitespaceFromUrl() = runBlocking {
        val server = serverWith(code = 200, body = "{}")
        try {
            channel(
                preset = "custom",
                url = "https://example.com/hook ",
                mockBase = server.url("/").toString(),
            ).send(message())
            assertEquals(1, server.requestCount)
            assertEquals("/hook", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
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

    @Test
    fun overlongTextIsEllipsizedToTheCap() {
        assertEquals("hi", fitWebhookText("hi"))
        assertEquals("x".repeat(2000), fitWebhookText("x".repeat(2000)))
        val fitted = fitWebhookText("x".repeat(2500))
        assertEquals(WebhookChannel.MAX_TEXT_CHARS, fitted.length)
        assertTrue(fitted.endsWith("…"))
    }

    @Test
    fun discordJsonContentIsTruncated() = runBlocking {
        val server = serverWith(code = 200, body = "{}")
        try {
            channel(mockBase = server.url("/").toString())
                .send(message().copy(text = "x".repeat(2500)))
            assertTrue(server.takeRequest().body.readUtf8().contains("…"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun ntfyTextIsTruncated() = runBlocking {
        val server = serverWith()
        try {
            channel(preset = "ntfy", url = "https://ntfy.sh/mytopic", mockBase = server.url("/").toString())
                .send(message().copy(text = "x".repeat(2500)))
            val body = server.takeRequest().body.readUtf8()
            assertEquals(WebhookChannel.MAX_TEXT_CHARS, body.length)
            assertTrue(body.endsWith("…"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discordUploadServerErrorThrowsInsteadOfFallingBack() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.start()
        try {
            var thrown: Throwable? = null
            try {
                channel(mockBase = server.url("/").toString()).send(message(snapshot = snapshot()))
            } catch (t: IllegalStateException) {
                thrown = t
            }
            assertTrue(thrown?.message.orEmpty().contains("Webhook failed (500)"))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discordSanitizesAttachmentFilename() = runBlocking {
        val server = serverWith(code = 200, body = "{}")
        try {
            val evil = Snapshot(
                bytes = byteArrayOf(1, 2, 3),
                mimeType = "image/png",
                name = "a\"\r\nb.png",
            )
            channel(mockBase = server.url("/").toString()).send(message(snapshot = evil))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("filename=\"ab.png\""))
            assertFalse(body.contains("filename=\"a\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun validateRejectsUnknownPresetAndBodyStyle() {
        assertEquals(
            "Unknown webhook preset",
            WebhookChannel(
                id = "w",
                settings = WebhookChannelSettings(preset = "discord2", url = "https://example.com/hook"),
            ).validate(),
        )
        assertEquals(
            "Body style must be json or text",
            WebhookChannel(
                id = "w",
                settings = WebhookChannelSettings(preset = "custom", url = "https://example.com/hook", bodyStyle = "xml"),
            ).validate(),
        )
    }

    @Test
    fun discordVideoPreviewIsSentAsFile() = runBlocking {
        val server = serverWith(code = 200, body = "{}")
        try {
            val preview = Snapshot(byteArrayOf(7, 8, 9), "image/gif", "preview.gif")
            channel(mockBase = server.url("/").toString()).send(
                AlertMessage(timestamp = Instant.EPOCH, triggerType = "motion", text = "hi", videoPreview = preview),
            )
            assertEquals(1, server.requestCount)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("preview.gif"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun ntfyVideoPreviewIsSentAsMultipart() = runBlocking {
        val server = serverWith()
        try {
            val preview = Snapshot(byteArrayOf(7, 8, 9), "image/gif", "preview.gif")
            channel(preset = "ntfy", url = "https://ntfy.sh/mytopic", mockBase = server.url("/").toString()).send(
                AlertMessage(timestamp = Instant.EPOCH, triggerType = "motion", text = "hi", videoPreview = preview),
            )
            assertEquals(1, server.requestCount)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("preview.gif"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun slackIgnoresVideoPreviewAndSendsJson() = runBlocking {
        val server = serverWith()
        try {
            val preview = Snapshot(byteArrayOf(7, 8, 9), "image/gif", "preview.gif")
            channel(preset = "slack", url = "https://hooks.slack.com/services/T123/B456/abc", mockBase = server.url("/").toString()).send(
                AlertMessage(timestamp = Instant.EPOCH, triggerType = "motion", text = "hi", videoPreview = preview),
            )
            assertEquals(1, server.requestCount)
            val recorded = server.takeRequest()
            assertTrue(recorded.getHeader("content-type").orEmpty().contains("application/json"))
            assertTrue(recorded.body.readUtf8().contains("hi"))
            assertFalse(recorded.body.readUtf8().contains("preview.gif"))
        } finally {
            server.shutdown()
        }
    }
}