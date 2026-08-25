package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.Snapshot
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `test/telegram_channel_test.dart`. */
class TelegramChannelTest {

    /** Rewrites api.telegram.org onto the local mock server. */
    private fun newChannel(mockBase: String): TelegramChannel {
        val base = mockBase.toHttpUrl()
        val client = TestHttp.rewritingClient(base)
        return TelegramChannel(
            id = "telegram",
            settings = TelegramChannelSettings(botToken = "123456:ABC-DEF", chatId = "42"),
            client = client,
        )
    }

    @Test
    fun validateAcceptsWellFormedToken() {
        assertNull(
            TelegramChannel(
                id = "telegram",
                settings = TelegramChannelSettings(botToken = "123456:ABC-DEF", chatId = "42"),
            ).validate(),
        )
    }

    @Test
    fun validateRejectsEmptyOrMalformedToken() {
        val bad = TelegramChannel(
            id = "telegram",
            settings = TelegramChannelSettings(botToken = "nope", chatId = "42"),
        )
        assertNotNull(bad.validate())
        val empty = TelegramChannel(
            id = "telegram",
            settings = TelegramChannelSettings(botToken = "", chatId = ""),
        )
        assertNotNull(empty.validate())
    }

    @Test
    fun sendPostsTextOnlyMessageWhenNoSnapshot() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.start()
        try {
            val c = newChannel(server.url("/").toString())
            c.send(
                AlertMessage(
                    timestamp = Instant.EPOCH,
                    triggerType = "motion",
                    text = "Motion detected in Hallway at 2026-01-01T00:00:00.000",
                ),
            )
            val recorded = server.takeRequest()
            assertEquals("/bot123456:ABC-DEF/sendMessage", recorded.path)
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"chat_id\":\"42\""))
            assertTrue(body.contains("Motion detected in Hallway"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendFallsBackToTextWhenPhotoFails() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"ok\":false}"))
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.start()
        try {
            val c = newChannel(server.url("/").toString())
            c.send(
                AlertMessage(
                    timestamp = Instant.EPOCH,
                    triggerType = "motion",
                    text = "Motion detected",
                    snapshot = Snapshot(bytes = ByteArray(0), mimeType = "image/png", name = "snap.png"),
                ),
            )
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val second = server.takeRequest()
            assertEquals("/bot123456:ABC-DEF/sendMessage", second.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendThrowsOnNonOkTextResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"ok\":false}"))
        server.start()
        try {
            val c = newChannel(server.url("/").toString())
            var thrown: Throwable? = null
            try {
                c.send(
                    AlertMessage(timestamp = Instant.EPOCH, triggerType = "motion", text = "Motion detected"),
                )
            } catch (t: IllegalStateException) {
                thrown = t
            }
            assertTrue(thrown?.message.orEmpty().contains("Telegram sendMessage failed"))
        } finally {
            server.shutdown()
        }
    }
}