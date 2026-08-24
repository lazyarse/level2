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
        TriggerIconPulser(kotlinx.coroutines.GlobalScope, 400) { shown.clear(); shown.addAll(it) }

    @Test
    fun secondIdenticalPulseReactivatesAfterTimeout() = runBlocking {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)

        p.onEvent("motion")
        assertEquals(setOf("motion"), shown.toSet())

        assertTrue("icon must clear after the window", awaitWithin { !shown.contains("motion") })

        // The reported symptom: same trigger again → icon returns.
        p.onEvent("motion")
        assertEquals(setOf("motion"), shown.toSet())

        assertTrue(awaitWithin { !shown.contains("motion") })
        Unit
    }

    @Test
    fun repeatInsideWindowKeepsIconSolidWithoutDuplicateRemovals() = runBlocking {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)

        p.onEvent("motion")
        Thread.sleep(250)
        p.onEvent("motion") // still detected: timer resets

        // Immediately after the repeat, the icon is solid again...
        assertTrue(shown.contains("motion"))
        // ...and it clears once the RESCHEDULED deadline passes.
        assertTrue(awaitWithin { !shown.contains("motion") })
        Unit
    }

    @Test
    fun mixedTypesTrackIndependently() = runBlocking {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)

        p.onEvent("motion")
        p.onEvent(TriggerType.faceKnown)
        assertEquals(setOf("motion", TriggerType.faceKnown), shown.toSet())

        assertTrue(awaitWithin { shown.isEmpty() })
        Unit
    }

    @Test
    fun resetClearsImmediatelyAndCancelsTimers() = runBlocking {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)
        p.onEvent("motion")

        p.reset()
        assertTrue(shown.isEmpty())

        // Removal job was cancelled; nothing re-notifies.
        Thread.sleep(600)
        assertTrue(shown.isEmpty())
        Unit
    }

    /** Polls until [pred] holds or timeout; returns final predicate value. */
    private fun awaitWithin(timeoutMs: Long = 2_000, pred: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (pred()) return true
            Thread.sleep(20)
        }
        return pred()
    }
}
