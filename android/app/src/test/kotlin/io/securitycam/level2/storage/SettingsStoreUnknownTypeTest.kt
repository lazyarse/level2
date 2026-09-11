package io.securitycam.level2.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.ChannelConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Wave 4: unknown channel types round-trip verbatim — no throw, no strip, no crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreUnknownTypeTest {

    private val secrets = InMemorySecretStore()

    private fun store(): SettingsStore {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return SettingsStore(context, secrets)
    }

    private fun withFutureType(): AppSettings = AppSettings.defaults().copyWith(
        channelConfigs = AppSettings.defaults().channelConfigs + ChannelConfig(
            id = "future",
            type = "future-type",
            enabled = true,
            settingsJson = mapOf("apiKey" to "s3cret", "endpoint" to "https://x.example"),
        ),
    )

    @Test
    fun unknownTypeRoundTripsVerbatimWithoutThrowing() = runBlocking {
        val s = store()
        s.save(withFutureType())

        // Blob keeps the entry verbatim (skip, don't strip, don't crash).
        val raw = s.rawJson().orEmpty()
        assertTrue(raw.contains("future-type"))
        assertTrue(raw.contains("s3cret"))

        val loaded = s.load()
        val future = loaded.channelConfigs.first { it.id == "future" }
        assertEquals("future-type", future.type)
        assertEquals("s3cret", future.settingsJson["apiKey"])
        assertEquals("https://x.example", future.settingsJson["endpoint"])
    }

    @Test
    fun unknownTypeWritesNoSecretsAndLoadInjectsNothing() = runBlocking {
        val s = store()
        s.save(withFutureType())
        assertNull(secrets.all["channel.future.apiKey"])

        val loaded = s.load()
        val future = loaded.channelConfigs.first { it.id == "future" }
        assertEquals("s3cret", future.settingsJson["apiKey"])
    }
}
