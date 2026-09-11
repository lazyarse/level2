package io.securitycam.level2.ui.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the locale-independent siren volume wire format. */
class SirenVolumeFormatTest {

    @Test
    fun formatsWithDotUnderCommaLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("0.80", formatSirenVolume(0.8f))
            assertEquals("1.00", formatSirenVolume(1f))
            // Round-trips through the field parser (toFloatOrNull expects a dot).
            assertEquals(0.8f, formatSirenVolume(0.8f).toFloatOrNull())
        } finally {
            Locale.setDefault(previous)
        }
    }
}
