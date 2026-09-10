package io.securitycam.level2.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the email port check behind the "Send test" button. */
class EmailPortErrorTest {

    @Test
    fun blankPortFallsBackTo587() {
        assertNull(emailPortError("email", emptyMap()))
        assertNull(emailPortError("email", mapOf("email.port" to "  ")))
    }

    @Test
    fun validPortsPass() {
        assertNull(emailPortError("email", mapOf("email.port" to "587")))
        assertNull(emailPortError("email", mapOf("email.port" to "465")))
        assertNull(emailPortError("email", mapOf("email.port" to "25")))
    }

    @Test
    fun garbagePortFails() {
        assertEquals(
            "Port must be a number from 1 to 65535",
            emailPortError("email", mapOf("email.port" to "abc")),
        )
        assertEquals(
            "Port must be a number from 1 to 65535",
            emailPortError("email", mapOf("email.port" to "0")),
        )
        assertEquals(
            "Port must be a number from 1 to 65535",
            emailPortError("email", mapOf("email.port" to "99999")),
        )
    }
}
