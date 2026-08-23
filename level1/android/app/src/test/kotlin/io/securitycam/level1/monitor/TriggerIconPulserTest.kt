package io.securitycam.level1.monitor

import io.securitycam.level1.core.TriggerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the 2026-08-23 "second motion never shows an icon" bug: the
 * old path consumed an accumulating StateFlow<Set>, whose equal-value
 * conflation swallowed repeat triggers after the icon timeout. Pulses are
 * edges, so identical consecutive triggers must each re-activate.
 *
 * Uses real time with a 200 ms window (plain runBlocking) — the behavior under
 * test is edge semantics, not clock precision.
 */
class TriggerIconPulserTest {

    private fun pulser(shown: MutableSet<String>) =
        TriggerIconPulser(kotlinx.coroutines.GlobalScope, 200) { shown.clear(); shown.addAll(it) }

    @Test
    fun secondIdenticalPulseReactivatesAfterTimeout() = runBlocking {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)

        p.onEvent("motion")
        assertEquals(setOf("motion"), shown.toSet())

        Thread.sleep(300)
        assertFalse("icon must clear after the window", shown.contains("motion"))

        // The reported symptom: same trigger again → icon returns.
        p.onEvent("motion")
        assertEquals(setOf("motion"), shown.toSet())

        Thread.sleep(300)
        assertFalse(shown.contains("motion"))
        Unit
    }

    @Test
    fun repeatInsideWindowKeepsIconSolidWithoutDuplicateRemovals() = runBlocking {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)

        p.onEvent("motion")
        Thread.sleep(100)
        p.onEvent("motion") // still detected: timer resets

        Thread.sleep(150)
        assertTrue("rescheduled window must still be active", shown.contains("motion"))
        Thread.sleep(200)
        assertFalse(shown.contains("motion"))
        Unit
    }

    @Test
    fun mixedTypesTrackIndependently() = runBlocking {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)

        p.onEvent("motion")
        p.onEvent(TriggerType.faceKnown)
        assertEquals(setOf("motion", TriggerType.faceKnown), shown.toSet())

        Thread.sleep(300)
        assertTrue(shown.isEmpty())
        Unit
    }

    @Test
    fun resetClearsImmediatelyAndCancelsTimers() = runBlocking {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)
        p.onEvent("motion")

        p.reset()
        assertTrue(shown.isEmpty())

        Thread.sleep(300)
        // Removal job was cancelled; nothing re-notifies.
        assertTrue(shown.isEmpty())
        Unit
    }
}
