package io.securitycam.level2.channels

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Wave 4: unknown (forward-version) channel types fail soft with null, not a throw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelRegistryUnknownTypeTest {

    @Test
    fun knownTypeBuildsSettings() {
        assertNotNull(ChannelRegistry.buildChannelSettings("log", emptyMap()))
        assertNotNull(
            ChannelRegistry.buildChannelSettings(
                "telegram",
                mapOf("botToken" to "t", "chatId" to "c"),
            ),
        )
    }

    @Test
    fun unknownTypeReturnsNullInsteadOfThrowing() {
        assertNull(
            ChannelRegistry.buildChannelSettings(
                "future-type",
                mapOf("apiKey" to "s3cret"),
            ),
        )
    }
}
