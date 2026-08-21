package io.securitycam.level1.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.ChannelConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Port of `test/settings_store_test.dart`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private val secrets = InMemorySecretStore()

    private fun store(): SettingsStore {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Robolectric gives every test method a fresh filesDir, so each store
        // gets its own DataStore file.
        return SettingsStore(context, secrets)
    }

    private fun withTelegram(token: String, chatId: String): AppSettings =
        AppSettings.defaults().copyWith(
            channelConfigs = listOf(
                ChannelConfig(
                    id = "telegram",
                    type = "telegram",
                    settingsJson = mapOf("botToken" to token, "chatId" to chatId),
                ),
            ),
        )

    @Test
    fun saveStripsTheBotTokenFromThePersistedJson() = runBlocking {
        val s = store()
        s.save(withTelegram("123:ABC", "42"))

        val raw = s.rawJson()
        assertTrue(raw != null)
        assertFalse(raw!!.contains("123:ABC"))
        assertTrue(raw.contains("\"chatId\":\"42\""))
    }

    @Test
    fun loadInjectsTheTokenFromTheSecretStoreIntoSettings() = runBlocking {
        val s = store()
        secrets.write("channel.telegram.botToken", "123:ABC")
        s.save(withTelegram("", "42"))

        val loaded = s.load()
        val tg = loaded.channelConfigs.first { c -> c.id == "telegram" }
        assertEquals("123:ABC", tg.settingsJson["botToken"])
        assertEquals("42", tg.settingsJson["chatId"])
    }

    @Test
    fun legacyInlineTokenMigratesToTheSecretStoreAndIsStripped() = runBlocking {
        val s = store()
        // Seed a legacy blob that still carries the token inline (bypasses save()).
        s.seedRaw(
            AppSettings.defaults().copyWith(
                channelConfigs = listOf(
                    ChannelConfig(
                        id = "telegram",
                        type = "telegram",
                        settingsJson = mapOf("botToken" to "legacy:token", "chatId" to "7"),
                    ),
                ),
            ),
        )

        val loaded = s.load()
        val tg = loaded.channelConfigs.first { c -> c.id == "telegram" }
        assertEquals("legacy:token", tg.settingsJson["botToken"])
        assertEquals("legacy:token", secrets.all["channel.telegram.botToken"])
        val raw = s.rawJson()
        assertFalse(raw.orEmpty().contains("legacy:token"))
    }

    @Test
    fun logChannelRoundTripsUnchanged() = runBlocking {
        val s = store()
        s.save(AppSettings.defaults())

        val loaded = s.load()
        assertTrue(loaded.channelConfigs.map { it.id }.contains("log"))
        assertTrue(secrets.all.isEmpty())
    }
}