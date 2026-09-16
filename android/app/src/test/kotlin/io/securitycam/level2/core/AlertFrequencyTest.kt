package io.securitycam.level2.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-channel alert frequency: throttle decisions and config persistence. */
class AlertFrequencyTest {

    private fun channel(mode: String, seconds: Int = 60): ChannelConfig =
        ChannelConfig(id = "c", type = "telegram", alertMode = mode, alertEverySeconds = seconds)

    @Test
    fun everyTriggerIsAlwaysDue() {
        val c = channel(AlertMode.EVERY_TRIGGER)
        assertTrue(c.isDue(null, 1_000L))
        assertTrue(c.isDue(1_000L, 1_001L))
    }

    @Test
    fun unknownModeFallsBackToEveryTrigger() {
        val c = channel("future_mode")
        assertTrue(c.isDue(null, 1_000L))
        assertTrue(c.isDue(1_000L, 1_001L))
    }

    @Test
    fun perWaveSendsOnlyUntilTheFirstSend() {
        val c = channel(AlertMode.PER_WAVE)
        assertTrue(c.isDue(null, 1_000L))
        assertFalse(c.isDue(1_000L, 1_001L))
        // A new wave passes null again, so it sends.
        assertTrue(c.isDue(null, 999_999L))
    }

    @Test
    fun throttledRespectsTheInterval() {
        val c = channel(AlertMode.THROTTLED, seconds = 60)
        assertTrue(c.isDue(null, 1_000L))
        assertFalse(c.isDue(1_000L, 30_000L))
        assertFalse(c.isDue(1_000L, 60_999L))
        assertTrue(c.isDue(1_000L, 61_000L))
    }

    @Test
    fun throttledWithNonPositiveIntervalIsAlwaysDue() {
        val c = channel(AlertMode.THROTTLED, seconds = 0)
        assertTrue(c.isDue(1_000L, 1_001L))
    }

    @Test
    fun defaultsPreserveEveryTrigger() {
        val c = ChannelConfig(id = "c", type = "telegram")
        assertEquals(AlertMode.EVERY_TRIGGER, c.alertMode)
        assertEquals(AlertMode.DEFAULT_EVERY_SECONDS, c.alertEverySeconds)
        assertTrue(c.isDue(1_000L, 1_001L))
    }

    @Test
    fun roundTripsThroughJson() {
        val c = channel(AlertMode.THROTTLED, seconds = 30)
        val back = ChannelConfig.fromJson(c.toJson())
        assertEquals(AlertMode.THROTTLED, back.alertMode)
        assertEquals(30, back.alertEverySeconds)
    }

    @Test
    fun missingFieldsDefaultToEveryTrigger() {
        val back = ChannelConfig.fromJson(mapOf("id" to "c", "type" to "telegram"))
        assertEquals(AlertMode.EVERY_TRIGGER, back.alertMode)
        assertEquals(AlertMode.DEFAULT_EVERY_SECONDS, back.alertEverySeconds)
    }
}
