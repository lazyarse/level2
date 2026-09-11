package io.securitycam.level2.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.DetectorConfig
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Wave 2: one-way cooldown migration + corrupt-blob backup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreWave2Test {

    private val secrets = InMemorySecretStore()

    private fun store(): SettingsStore {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return SettingsStore(context, secrets)
    }

    @Test
    fun migrationRunsOnceAndNeverRewritesAnIntentionalLegacyValue() = runBlocking {
        val s = store()
        s.seedRaw(
            AppSettings.defaults().copyWith(
                detectorConfigs = mapOf(
                    TriggerType.motion to DetectorConfig(
                        type = TriggerType.motion,
                        threshold = 0.03,
                        cooldown = Duration.ofSeconds(120),
                    ),
                ),
            ),
        )

        val first = s.load()
        assertEquals(5_000L, first.detectorConfigs.getValue(TriggerType.motion).cooldown.toMillis())
        assertTrue(first.cooldownsMigrated)
        assertTrue(s.rawJson().orEmpty().contains("cooldownsMigrated"))

        // User now intentionally picks 60s and saves: the next load must keep it.
        val intentional = first.copyWith(
            detectorConfigs = mapOf(
                TriggerType.motion to DetectorConfig(
                    type = TriggerType.motion,
                    threshold = 0.03,
                    cooldown = Duration.ofSeconds(60),
                ),
            ),
        )
        s.save(intentional)

        val second = s.load()
        assertEquals(60_000L, second.detectorConfigs.getValue(TriggerType.motion).cooldown.toMillis())
    }

    @Test
    fun corruptBlobFallsBackToDefaultsButStashesTheRawText() = runBlocking {
        val s = store()
        val raw = "{this is not json{{{"
        s.seedRawJson(raw)

        val loaded = s.load()
        assertEquals(AppSettings.defaults().cameraName, loaded.cameraName)
        assertEquals(raw, s.corruptBackupJson())
    }
}
