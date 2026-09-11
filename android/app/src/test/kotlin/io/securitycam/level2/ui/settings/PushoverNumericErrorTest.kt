package io.securitycam.level2.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the pushover numeric checks behind the "Send test" button. */
class PushoverNumericErrorTest {

    @Test
    fun blankFieldsFallBackToDefaults() {
        assertNull(pushoverNumericError("pushover", emptyMap()))
        assertNull(
            pushoverNumericError(
                "pushover",
                mapOf("pushover.priority" to "  ", "pushover.retrySeconds" to "", "pushover.expireSeconds" to ""),
            ),
        )
    }

    @Test
    fun validNumbersPass() {
        assertNull(
            pushoverNumericError(
                "pushover",
                mapOf("pushover.priority" to "2", "pushover.retrySeconds" to "60", "pushover.expireSeconds" to "3600"),
            ),
        )
        assertNull(pushoverNumericError("pushover", mapOf("pushover.priority" to "-2")))
    }

    @Test
    fun garbagePriorityFails() {
        assertEquals(
            "Priority must be a whole number from -2 to 2",
            pushoverNumericError("pushover", mapOf("pushover.priority" to "high")),
        )
        assertEquals(
            "Priority must be a whole number from -2 to 2",
            pushoverNumericError("pushover", mapOf("pushover.priority" to "3")),
        )
    }

    @Test
    fun garbageEmergencyWindowsFail() {
        assertEquals(
            "Emergency retry must be a whole number of seconds",
            pushoverNumericError("pushover", mapOf("pushover.retrySeconds" to "soon")),
        )
        assertEquals(
            "Emergency expiry must be a whole number of seconds",
            pushoverNumericError("pushover", mapOf("pushover.expireSeconds" to "0")),
        )
    }
}
