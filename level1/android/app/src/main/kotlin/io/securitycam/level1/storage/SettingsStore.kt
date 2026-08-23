package io.securitycam.level1.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.securitycam.level1.channels.ChannelRegistry
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.ChannelConfig
import io.securitycam.level1.core.LiveViewSettings
import io.securitycam.level1.core.TriggerType
import java.time.Duration
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists [AppSettings] as a JSON blob under `app_settings_v1` in DataStore,
 * keeping channel secrets out of the blob (they live in [SecretStore]).
 * Port of `lib/storage/settings_store.dart`.
 */
class SettingsStore(
    context: Context,
    private val secrets: SecretStore,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val dataStore = sharedDataStore(context)

    /** Loads settings, injecting channel secrets and migrating legacy inline tokens. */
    suspend fun load(): AppSettings {
        val raw = dataStore.data.first()[KEY]
        val settings = if (raw == null) AppSettings.defaults() else tryParse(raw)
        val withChannelSecrets = injectSecrets(settings)
        return migrateLegacyCooldowns(injectLiveViewSecret(withChannelSecrets))
    }

    /**
     * One-way normalization for blobs written before the 2026-08-23 cooldown
     * change: any detector still carrying a shipped legacy default (60s/120s/
     * 5min) is moved to the new 5s baseline. Health keeps its long anti-spam
     * window. Re-applies harmlessly on every load; user-tuned values that
     * don't equal a legacy default pass through untouched.
     */
    private fun migrateLegacyCooldowns(settings: AppSettings): AppSettings {
        val legacy = setOf(60_000L, 120_000L, 300_000L)
        var changed = false
        val configs = settings.detectorConfigs.mapValues { (type, cfg) ->
            val ms = cfg.cooldown.toMillis()
            if (ms in legacy && type != io.securitycam.level1.core.TriggerType.health) {
                changed = true
                cfg.copy(cooldown = Duration.ofSeconds(5))
            } else {
                cfg
            }
        }
        return if (changed) settings.copyWith(detectorConfigs = configs) else settings
    }

    /** Saves settings with all channel secrets stripped into the secret store. */
    suspend fun save(settings: AppSettings) {
        val lv = settings.liveView
        if (lv.password.isNotEmpty()) {
            secrets.write(liveViewSecretKey(), lv.password)
        }
        val sanitized = settings.copyWith(
            channelConfigs = settings.channelConfigs.map { c ->
                c.copyWith(settingsJson = stripSecrets(c))
            },
            liveView = lv.copy(password = ""),
        )
        dataStore.edit { it[KEY] = mapToJsonString(sanitized.toJson()) }
    }

    /** Raw persisted blob (tests/diagnostics). */
    suspend fun rawJson(): String? = dataStore.data.first()[KEY]

    /** Seeds the raw blob directly, bypassing secret-stripping (test hook,
     * mirrors Dart's SharedPreferences.setMockInitialValues). */
    suspend fun seedRaw(settings: AppSettings) {
        dataStore.edit { it[KEY] = mapToJsonString(settings.toJson()) }
    }

    private suspend fun tryParse(raw: String): AppSettings = try {
        AppSettings.fromJson(jsonStringToMap(raw))
    } catch (_: Exception) {
        AppSettings.defaults()
    }

    private suspend fun injectSecrets(settings: AppSettings): AppSettings {
        var migrated = false
        val channels = mutableListOf<ChannelConfig>()
        for (c in settings.channelConfigs) {
            val typed = try {
                ChannelRegistry.buildChannelSettings(c.type, c.settingsJson)
            } catch (_: Exception) {
                channels.add(c)
                continue
            }
            var json = c.settingsJson
            var injected = false
            for (field in typed.secretFields) {
                val key = secretKey(c.id, field)
                val inline = json[field]
                if (inline is String && inline.isNotEmpty()) {
                    // Legacy token still persisted inline → move it to the secret
                    // store; the in-memory value stays usable, save() strips it.
                    secrets.write(key, inline)
                    migrated = true
                } else {
                    val stored = secrets.read(key)
                    if (!stored.isNullOrEmpty()) {
                        json = json + (field to stored)
                        injected = true
                    }
                }
            }
            channels.add(if (injected) c.copyWith(settingsJson = json) else c)
        }
        val next = settings.copyWith(channelConfigs = channels)
        if (migrated) save(next)
        return next
    }

    private fun liveViewSecretKey(): String = "liveview.password"

    private suspend fun injectLiveViewSecret(settings: AppSettings): AppSettings {
        val lv = settings.liveView
        val inline = lv.password
        if (inline.isNotEmpty()) {
            secrets.write(liveViewSecretKey(), inline)
            return settings
        }
        val stored = secrets.read(liveViewSecretKey())
        if (!stored.isNullOrEmpty()) {
            return settings.copyWith(liveView = lv.copy(password = stored))
        }
        return settings
    }

    private fun stripSecrets(config: ChannelConfig): Map<String, Any?> = try {
        val typed = ChannelRegistry.buildChannelSettings(config.type, config.settingsJson)
        if (typed.secretFields.isEmpty()) {
            config.settingsJson
        } else {
            config.settingsJson.filterKeys { it !in typed.secretFields }
        }
    } catch (_: Exception) {
        config.settingsJson
    }

    companion object {
        const val FILE_NAME = "settings"
        val KEY: Preferences.Key<String> = stringPreferencesKey("app_settings_v1")

        /**
         * Process-wide singleton keyed by file path: constructing multiple
         * active DataStores on the same file throws `IllegalStateException`
         * (surfaced by the instrumentation suite when MonitorViewModel and
         * the settings screen both opened one).
         */
        private val sharedStores =
            mutableMapOf<String, androidx.datastore.core.DataStore<Preferences>>()

        private fun sharedDataStore(context: Context): androidx.datastore.core.DataStore<Preferences> {
            val file = context.applicationContext.preferencesDataStoreFile(FILE_NAME)
            return synchronized(sharedStores) {
                sharedStores.getOrPut(file.absolutePath) {
                    PreferenceDataStoreFactory.create(
                        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    ) { file }
                }
            }
        }

        fun secretKey(channelId: String, field: String): String = "channel.$channelId.$field"

        private fun jsonStringToMap(raw: String): Map<String, Any?> =
            jsonToAny(JSONObject(raw)) as Map<String, Any?>

        private fun mapToJsonString(map: Map<String, Any?>): String =
            (anyToJson(map) as JSONObject).toString()

        private fun jsonToAny(v: Any?): Any? = when (v) {
            is JSONObject -> {
                val m = LinkedHashMap<String, Any?>()
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    m[k] = jsonToAny(v.opt(k))
                }
                m
            }
            is JSONArray -> (0 until v.length()).map { jsonToAny(v.get(it)) }
            else -> v
        }

        private fun anyToJson(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val o = JSONObject()
                for ((k, value) in v) o.put(k as String, anyToJson(value))
                o
            }
            is List<*> -> {
                val a = JSONArray()
                for (item in v) a.put(anyToJson(item))
                a
            }
            else -> v
        }
    }
}