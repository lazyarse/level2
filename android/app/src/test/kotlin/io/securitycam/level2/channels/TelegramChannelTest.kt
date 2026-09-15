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
    private fun newChannel(
        mockBase: String,
        testSnapshot: () -> Snapshot = { Snapshot(byteArrayOf(1, 2, 3), "image/jpeg", "test-snapshot.jpg") },
    ): TelegramChannel {
        val base = mockBase.toHttpUrl()
        val client = TestHttp.rewritingClient(base)
        return TelegramChannel(
            id = "telegram",
            settings = TelegramChannelSettings(botToken = "123456:ABC-DEF", chatId = "42"),
            client = client,
            testSnapshot = testSnapshot,
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
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}"),
        )
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
            assertTrue(thrown?.message.orEmpty().contains("Bad Request: chat not found"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendTestAttemptsPhotoBeforeText() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.start()
        try {
            val c = newChannel(server.url("/").toString())
            c.sendTest()
            assertEquals(1, server.requestCount)
            val recorded = server.takeRequest()
            assertEquals("/bot123456:ABC-DEF/sendPhoto", recorded.path)
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("Security Cam: test alert"))
            assertTrue(body.contains("test-snapshot.jpg"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendTestFallsBackToTextWhenPhotoFails() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"ok\":false}"))
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.start()
        try {
            val c = newChannel(server.url("/").toString())
            c.sendTest()
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val second = server.takeRequest()
            assertEquals("/bot123456:ABC-DEF/sendMessage", second.path)
            assertTrue(second.body.readUtf8().contains("Security Cam: test alert"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendTestDegradesToTextWhenSnapshotFails() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.start()
        try {
            val c = newChannel(server.url("/").toString(), testSnapshot = { error("no bitmap") })
            c.sendTest()
            assertEquals(1, server.requestCount)
            val recorded = server.takeRequest()
            assertEquals("/bot123456:ABC-DEF/sendMessage", recorded.path)
            assertTrue(recorded.body.readUtf8().contains("Security Cam: test alert"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendWithVideoPreviewUsesSendAnimation() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.start()
        try {
            val c = newChannel(server.url("/").toString())
            val preview = Snapshot(byteArrayOf(7, 8, 9), "image/gif", "preview.gif")
            c.send(
                AlertMessage(
                    timestamp = Instant.EPOCH,
                    triggerType = "motion",
                    text = "Motion with preview",
                    videoPreview = preview,
                ),
            )
            assertEquals(1, server.requestCount)
            val recorded = server.takeRequest()
            assertEquals("/bot123456:ABC-DEF/sendAnimation", recorded.path)
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("preview.gif"))
            assertTrue(body.contains("Motion with preview"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendAnimationFailureThrows() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"ok\":false,\"description\":\"FILE_TOO_BIG\"}"))
        server.start()
        try {
            val c = newChannel(server.url("/").toString())
            var thrown: Throwable? = null
            try {
                c.send(
                    AlertMessage(
                        timestamp = Instant.EPOCH,
                        triggerType = "motion",
                        text = "hi",
                        videoPreview = Snapshot(byteArrayOf(1), "image/gif", "preview.gif"),
                    ),
                )
            } catch (e: IllegalStateException) {
                thrown = e
            }
            assertTrue(thrown?.message.orEmpty().contains("sendAnimation failed"))
            assertTrue(thrown?.message.orEmpty().contains("FILE_TOO_BIG"))
        } finally {
            server.shutdown()
        }
    }
}