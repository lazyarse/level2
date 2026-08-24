package io.securitycam.level1.monitor

import io.securitycam.level1.core.TriggerType
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the 2026-08-23 "second motion never shows an icon" bug: the
 * old path consumed an accumulating StateFlow<Set>, whose equal-value
 * conflation swallowed repeat triggers after the icon timeout. Pulses are
 * edges, so identical consecutive triggers must each re-activate.
 *
 * Uses [runTest] virtual time — the pulser schedules via `delay` inside the
 * injected scope, so advancing the clock is fully deterministic and immune to
 * machine load (the real-time version flaked under parallel Gradle workers).
 */
class TriggerIconPulserTest {

    private fun TestScope.pulser(shown: MutableSet<String>) =
        TriggerIconPulser(this, 400) { shown.clear(); shown.addAll(it) }

    @Test
    fun secondIdenticalPulseReactivatesAfterTimeout() = runTest {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)

        p.onEvent("motion")
        runCurrent()
        assertEquals(setOf("motion"), shown.toSet())

        advanceTimeBy(400)
        runCurrent()
        assertTrue("icon must clear after the window", shown.isEmpty())

        // The reported symptom: same trigger again → icon returns.
        p.onEvent("motion")
        runCurrent()
        assertEquals(setOf("motion"), shown.toSet())

        advanceTimeBy(400)
        runCurrent()
        assertTrue(shown.isEmpty())
    }

    @Test
    fun repeatInsideWindowKeepsIconSolidWithoutDuplicateRemovals() = runTest {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)

        p.onEvent("motion")
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        p.onEvent("motion") // still detected: timer resets
        runCurrent()

        // Immediately after the repeat, the icon is solid again...
        assertTrue(shown.contains("motion"))
        // The ORIGINAL deadline passes while the icon stays solid...
        advanceTimeBy(150)
        runCurrent()
        assertTrue(shown.contains("motion"))
        // ...and it clears once the RESCHEDULED deadline (250+400) passes.
        advanceTimeBy(500)
        runCurrent()
        assertTrue(shown.isEmpty())
    }

    @Test
    fun mixedTypesTrackIndependently() = runTest {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)

        p.onEvent("motion")
        runCurrent()
        p.onEvent(TriggerType.faceKnown)
        runCurrent()
        assertEquals(setOf("motion", TriggerType.faceKnown), shown.toSet())

        advanceTimeBy(400)
        runCurrent()
        assertTrue(shown.isEmpty())
    }

    @Test
    fun resetClearsImmediatelyAndCancelsTimers() = runTest {
        val shown = mutableSetOf<String>()
        val p = pulser(shown)
        p.onEvent("motion")
        runCurrent()

        p.reset()
        assertTrue(shown.isEmpty())

        // Removal job was cancelled; nothing re-notifies later.
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(shown.isEmpty())
    }
}
