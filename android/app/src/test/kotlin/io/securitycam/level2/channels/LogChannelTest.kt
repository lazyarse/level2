package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The log channel feeds the shared viewer feed (AlertLog is process-global). */
class LogChannelTest {

    @Before
    fun clearBefore() = runBlocking { AlertLog.clear() }

    @After
    fun clearAfter() = runBlocking { AlertLog.clear() }

    @Test
    fun sendAppendsToAlertLog() = runBlocking {
        val c = LogChannel()

        c.send(AlertMessage(Instant.EPOCH, "motion", "Motion detected in Hallway"))

        val entries = AlertLog.flow.value
        assertEquals(1, entries.size)
        assertEquals("Motion detected in Hallway", entries.single().text)
        assertEquals("log", entries.single().channelId)
        assertEquals(1, c.sent.size)
    }

    @Test
    fun sendTestLogsTestAlert() = runBlocking {
        LogChannel().sendTest()

        assertEquals("test", AlertLog.flow.value.single().triggerType)
    }
}
