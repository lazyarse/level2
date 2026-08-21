package io.securitycam.level1.channels

import io.securitycam.level1.core.AlertMessage
import io.securitycam.level1.core.Snapshot
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

/** Port of `test/pushover_channel_test.dart`. */
class PushoverChannelTest {

    private fun channel(mockBase: String? = null): PushoverChannel = PushoverChannel(
        id = "pushover",
        enabled = true,
        settings = PushoverChannelSettings(
            appToken = "apptok",
            userKey = "userkey",
            sound = "siren",
            priority = 1,
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

    private fun serverWith(body: String = "{}"): MockWebServer = MockWebServer().apply {
        enqueue(MockResponse().setBody(body))
        start()
    }

    @Test
    fun sendPostsFormEncodedFieldsWithoutASnapshot() = runBlocking {
        val server = serverWith()
        try {
            val c = channel(server.url("/").toString())
            c.send(message())
            assertEquals(1, server.requestCount)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertTrue(recorded.getHeader("content-type").orEmpty().contains("application/x-www-form-urlencoded"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("token=apptok"))
            assertTrue(body.contains("user=userkey"))
            assertTrue(body.contains("message=Motion"))
            assertTrue(body.contains("sound=siren"))
            assertTrue(body.contains("priority=1"))
            assertFalse(body.contains("attachment"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendUploadsTheSnapshotAsAnAttachment() = runBlocking {
        val server = serverWith()
        try {
            val c = channel(server.url("/").toString())
            c.send(message(snapshot = snapshot()))
            assertEquals(1, server.requestCount)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("attachment"))
            assertTrue(body.contains("snap.png"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendTestPostsATestAlert() = runBlocking {
        val server = serverWith()
        try {
            val c = channel(server.url("/").toString())
            c.sendTest()
            val body = server.takeRequest().body.readUtf8()
            val fields = body.split('&').associate {
                val (k, v) = it.split('=', limit = 2)
                k to java.net.URLDecoder.decode(v, "UTF-8")
            }
            assertEquals("Security Cam: test alert", fields["message"])
            assertEquals("apptok", fields["token"])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun non2xxResponseThrowsAReadableError() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(401).setBody("boom"))
            start()
        }
        try {
            val c = channel(server.url("/").toString())
            var thrown: Throwable? = null
            try {
                c.sendTest()
            } catch (t: IllegalStateException) {
                thrown = t
            }
            assertTrue(thrown?.message.orEmpty().contains("Pushover failed (401)"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun validateRequiresAppTokenAndUserKey() {
        assertEquals("App token is required", PushoverChannel(id = "p", settings = PushoverChannelSettings()).validate())
        assertEquals(
            "User key is required",
            PushoverChannel(id = "p", settings = PushoverChannelSettings(appToken = "a")).validate(),
        )
        assertNull(channel().validate())
    }

    @Test
    fun appTokenAndUserKeyAreSecretFields() {
        assertEquals(listOf("appToken", "userKey"), PushoverChannelSettings(appToken = "a", userKey = "u").secretFields)
    }
}