package io.securitycam.level2.channels

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Unit tests for the alert-log ring buffer (no Android APIs). */
class AlertLogTest {

    @Before
    fun clearBefore() = runBlocking { AlertLog.clear() }

    @After
    fun clearAfter() = runBlocking { AlertLog.clear() }

    private fun entry(text: String, second: Long = 0L) = AlertLogEntry(
        timestamp = Instant.EPOCH.plusSeconds(second),
        channelId = "log",
        triggerType = "motion",
        text = text,
    )

    @Test
    fun appendsAreVisibleInFlowOrder() = runBlocking {
        AlertLog.add(entry("first", 1))
        AlertLog.add(entry("second", 2))

        val texts = AlertLog.flow.value.map { it.text }
        assertEquals(listOf("first", "second"), texts)
    }

    @Test
    fun capEvictsOldest() = runBlocking {
        for (i in 0 until AlertLog.MAX_ENTRIES + 5) {
            AlertLog.add(entry("m$i", i.toLong()))
        }

        val texts = AlertLog.flow.value.map { it.text }
        assertEquals(AlertLog.MAX_ENTRIES, texts.size)
        assertEquals("m5", texts.first())
        assertEquals("m${AlertLog.MAX_ENTRIES + 4}", texts.last())
    }

    @Test
    fun clearEmpties() = runBlocking {
        AlertLog.add(entry("x"))
        AlertLog.clear()

        assertTrue(AlertLog.flow.value.isEmpty())
    }
}
