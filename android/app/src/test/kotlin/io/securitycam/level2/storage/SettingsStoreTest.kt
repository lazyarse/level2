package io.securitycam.level2.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.ChannelConfig
import io.securitycam.level2.core.LiveViewSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun saveWritesChannelSecretsToTheSecretStore() = runBlocking {
        val s = store()
        s.save(withTelegram("123:ABC", "42"))

        assertEquals("123:ABC", secrets.all["channel.telegram.botToken"])
    }

    @Test
    fun emailPasswordRoundTripsViaTheSecretStore() = runBlocking {
        val s = store()
        s.save(
            AppSettings.defaults().copyWith(
                channelConfigs = listOf(
                    ChannelConfig(
                        id = "email",
                        type = "email",
                        settingsJson = mapOf(
                            "host" to "smtp.ethereal.email",
                            "port" to 587,
                            "username" to "user@ethereal.email",
                            "password" to "s3cret-app-pass",
                            "from" to "user@ethereal.email",
                            "to" to "me@example.com",
                            "useTls" to false,
                        ),
                    ),
                ),
            ),
        )

        val raw = s.rawJson()
        assertTrue(raw != null)
        assertFalse(raw!!.contains("s3cret-app-pass"))
        assertTrue(raw.contains("smtp.ethereal.email"))

        val loaded = s.load()
        val email = loaded.channelConfigs.first { c -> c.id == "email" }
        assertEquals("s3cret-app-pass", email.settingsJson["password"])
        assertEquals("smtp.ethereal.email", email.settingsJson["host"])
        assertEquals("me@example.com", email.settingsJson["to"])
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
    fun saveDeletesSecretsOfRemovedChannels() = runBlocking {
        val s = store()
        secrets.write("channel.email.password", "old-pass")
        secrets.write("channel.telegram.botToken", "keep-me")
        s.seedRaw(
            AppSettings.defaults().copyWith(
                channelConfigs = listOf(
                    ChannelConfig(id = "email", type = "email", settingsJson = mapOf("host" to "h")),
                    ChannelConfig(id = "telegram", type = "telegram", settingsJson = mapOf("chatId" to "1")),
                ),
            ),
        )

        s.save(
            AppSettings.defaults().copyWith(
                channelConfigs = listOf(
                    ChannelConfig(id = "telegram", type = "telegram", settingsJson = mapOf("chatId" to "1")),
                ),
            ),
        )

        assertNull(secrets.all["channel.email.password"])
        assertEquals("keep-me", secrets.all["channel.telegram.botToken"])
    }

    @Test
    fun saveDropsPristinePlaceholdersAndPrunesTheirSecrets() = runBlocking {
        val s = store()
        secrets.write("channel.email.password", "lingering")
        val configs = listOf(
            ChannelConfig(id = "log", type = "log", enabled = true),
            ChannelConfig(id = "email", type = "email", enabled = false),
        )
        s.seedRaw(AppSettings.defaults().copyWith(channelConfigs = configs))

        s.save(AppSettings.defaults().copyWith(channelConfigs = configs))

        val raw = s.rawJson().orEmpty()
        assertFalse(raw.contains("\"email\""))
        assertNull(secrets.all["channel.email.password"])
        assertEquals(listOf("log"), s.load().channelConfigs.map { it.id })
    }

    @Test
    fun liveViewInlinePasswordMigratesAndBlobIsStripped() = runBlocking {
        val s = store()
        // Legacy blob shape: current serializers never emit an inline
        // password, so the JSON is crafted literally.
        s.seedRawJson(
            """
            {"cameraName":"Hallway",
             "liveView":{"enabled":true,"mode":"server","port":8554,
               "username":"admin","password":"s3cret"},
             "channelConfigs":[{"id":"log","type":"log","enabled":true}]}
            """.trimIndent(),
        )

        val loaded = s.load()

        assertEquals("s3cret", loaded.liveView.password)
        assertEquals("s3cret", secrets.all["liveview.password"])
        assertFalse(s.rawJson().orEmpty().contains("s3cret"))
    }

    @Test
    fun logChannelRoundTripsUnchanged() = runBlocking {
        val s = store()
        s.save(AppSettings.defaults())

        val loaded = s.load()
        assertTrue(loaded.channelConfigs.map { it.id }.contains("log"))
        assertTrue(secrets.all.isEmpty())
    }

    @Test
    fun liveViewPasswordStrippedAndInjectedViaSecretStore() = runBlocking {
        val s = store()
        val settings = AppSettings.defaults().copyWith(
            liveView = LiveViewSettings(
                enabled = true,
                mode = "server",
                port = 8554,
                username = "admin",
                password = "s3cret",
            ),
        )
        s.save(settings)

        val raw = s.rawJson()
        assertTrue(raw != null)
        assertFalse(raw!!.contains("s3cret"))
        assertTrue(raw.contains("\"port\":8554"))

        val loaded = s.load()
        assertEquals("s3cret", loaded.liveView.password)
        assertEquals("admin", loaded.liveView.username)
        assertEquals(8554, loaded.liveView.port)
    }

    @Test
    fun liveViewDefaultsRoundTrip() = runBlocking {
        val s = store()
        s.save(AppSettings.defaults())

        val loaded = s.load()
        assertEquals(LiveViewSettings(), loaded.liveView)
    }

    @Test
    fun legacyCooldownDefaultsMigrateToFiveSeconds() = runBlocking {
        val s = store()
        // Blob written by an older build: shipped legacy defaults (motion 120s,
        // face fallback 60s, loud_noise 5min). Health keeps its long window.
        val legacyConfigs = listOf(
            io.securitycam.level2.detection.DetectorConfig(
                type = io.securitycam.level2.core.TriggerType.motion,
                threshold = 0.03,
                cooldown = java.time.Duration.ofSeconds(120),
            ),
            io.securitycam.level2.detection.DetectorConfig(
                type = io.securitycam.level2.core.TriggerType.face,
                threshold = 0.7,
                cooldown = java.time.Duration.ofSeconds(60),
            ),
            io.securitycam.level2.detection.DetectorConfig(
                type = io.securitycam.level2.core.TriggerType.loudNoise,
                threshold = 0.85,
                cooldown = java.time.Duration.ofMinutes(5),
            ),
            io.securitycam.level2.detection.DetectorConfig(
                type = io.securitycam.level2.core.TriggerType.health,
                enabled = true,
                cooldown = java.time.Duration.ofMinutes(5),
            ),
        )
        s.seedRaw(AppSettings.defaults().copyWith(detectorConfigs = legacyConfigs.associateBy { it.type }))

        val loaded = s.load()

        fun cooldown(type: String) =
            loaded.detectorConfigs.getValue(type).cooldown.toMillis()
        assertEquals(5_000L, cooldown("motion"))
        assertEquals(5_000L, cooldown("face"))
        assertEquals(5_000L, cooldown("loud_noise"))
        // Health's anti-spam window is intentionally preserved.
        assertEquals(300_000L, cooldown("health"))
    }
}