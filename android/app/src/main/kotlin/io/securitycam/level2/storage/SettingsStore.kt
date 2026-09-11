package io.securitycam.level2.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.securitycam.level2.channels.ChannelRegistry
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.ChannelConfig
import io.securitycam.level2.core.isPristinePlaceholder
import io.securitycam.level2.core.LiveViewSettings
import io.securitycam.level2.core.TriggerType
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
) {
    private val dataStore = sharedDataStore(context)

    /** Loads settings, injecting channel secrets and migrating legacy inline tokens. */
    suspend fun load(): AppSettings {
        val raw = dataStore.data.first()[KEY]
        val settings = if (raw == null) AppSettings.defaults() else tryParse(raw)
        val withChannelSecrets = injectSecrets(settings)
        val (withLiveView, liveViewMigrated) = injectLiveViewSecret(withChannelSecrets)
        val (withCooldowns, cooldownsMigrated) = migrateLegacyCooldownsOnce(withLiveView)
        val (final, cloudBackupMigrated) = injectCloudBackupSecret(withCooldowns)
        // Strip migrated inline passwords from the blob right away (channels
        // already do this); otherwise the plaintext lingers until the next
        // manual save. In-memory values are untouched.
        if (liveViewMigrated || cloudBackupMigrated || cooldownsMigrated) save(final)
        return final
    }

    /**
     * One-way normalization for blobs written before the 2026-08-23 cooldown
     * change: any detector still carrying a shipped legacy default (60s/120s/
     * 5min) is moved to the new 5s baseline. Health keeps its long anti-spam
     * window. Runs at most once per blob (guarded by
     * [AppSettings.cooldownsMigrated]) so an intentional 60s/120s/5min choice
     * made after migration is never rewritten on a later load.
     *
     * @return the (possibly) migrated settings plus whether the blob must be
     *   re-saved (migration ran, even if no value matched a legacy default —
     *   the flag itself still needs persisting).
     */
    private fun migrateLegacyCooldownsOnce(settings: AppSettings): Pair<AppSettings, Boolean> {
        if (settings.cooldownsMigrated) return settings to false
        val migrated = migrateLegacyCooldowns(settings)
        return migrated.copy(cooldownsMigrated = true) to true
    }
    private fun migrateLegacyCooldowns(settings: AppSettings): AppSettings {
        val legacy = setOf(60_000L, 120_000L, 300_000L)
        var changed = false
        val configs = settings.detectorConfigs.mapValues { (type, cfg) ->
            val ms = cfg.cooldown.toMillis()
            if (ms in legacy && type != io.securitycam.level2.core.TriggerType.health) {
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
        val cb = settings.cloudBackup
        if (cb.password.isNotEmpty()) {
            secrets.write(cloudBackupSecretKey(), cb.password)
        }
        // Never-configured placeholder accounts are dropped from the blob.
        // Pruning against the filtered ids also wipes any lingering secret
        // of a placeholder the user emptied after configuring.
        val live = settings.channelConfigs.filterNot { it.isPristinePlaceholder() }
        pruneRemovedChannelSecrets(live.map { it.id }.toSet())
        for (c in live) {
            persistChannelSecrets(c)
        }
        val sanitized = settings.copyWith(
            channelConfigs = live.map { c ->
                c.copyWith(settingsJson = stripSecrets(c))
            },
            liveView = lv.copy(password = ""),
            cloudBackup = cb.copy(password = ""),
        )
        dataStore.edit { it[KEY] = mapToJsonString(sanitized.toJson()) }
    }

    /** Raw persisted blob (tests/diagnostics). */
    suspend fun rawJson(): String? = dataStore.data.first()[KEY]

    /** Stashed corrupt blob from the last failed parse, if any. */
    suspend fun corruptBackupJson(): String? = dataStore.data.first()[CORRUPT_BACKUP_KEY]

    /** Seeds the raw blob directly, bypassing secret-stripping (test hook,
     * mirrors Dart's SharedPreferences.setMockInitialValues). */
    suspend fun seedRaw(settings: AppSettings) {
        dataStore.edit { it[KEY] = mapToJsonString(settings.toJson()) }
    }

    /** Seeds a literal blob string (test hook for legacy shapes that current
     * serializers can no longer produce, e.g. inline passwords). */
    suspend fun seedRawJson(json: String) {
        dataStore.edit { it[KEY] = json }
    }

    private suspend fun tryParse(raw: String): AppSettings = try {
        AppSettings.fromJson(jsonStringToMap(raw))
    } catch (e: Exception) {
        // A corrupt blob used to reset to defaults silently, destroying the
        // only copy of the user's config. Log the failure with the blob size
        // and stash the raw text under a backup key before falling back, so
        // the data survives for diagnosis/recovery.
        android.util.Log.w("SettingsStore", "settings blob corrupt (${raw.length} chars); using defaults", e)
        runCatching {
            dataStore.edit { it[CORRUPT_BACKUP_KEY] = raw }
        }
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

    private suspend fun injectLiveViewSecret(settings: AppSettings): Pair<AppSettings, Boolean> {
        val lv = settings.liveView
        val inline = lv.password
        if (inline.isNotEmpty()) {
            secrets.write(liveViewSecretKey(), inline)
            return settings to true
        }
        val stored = secrets.read(liveViewSecretKey())
        if (!stored.isNullOrEmpty()) {
            return settings.copyWith(liveView = lv.copy(password = stored)) to false
        }
        return settings to false
    }

    private fun cloudBackupSecretKey(): String = "cloudbackup.password"

    private suspend fun injectCloudBackupSecret(settings: AppSettings): Pair<AppSettings, Boolean> {
        val cb = settings.cloudBackup
        val inline = cb.password
        if (inline.isNotEmpty()) {
            secrets.write(cloudBackupSecretKey(), inline)
            return settings to true
        }
        val stored = secrets.read(cloudBackupSecretKey())
        if (!stored.isNullOrEmpty()) {
            return settings.copyWith(cloudBackup = cb.copy(password = stored)) to false
        }
        return settings to false
    }

    /**
     * Writes a channel's non-empty secret fields into the secret store (the
     * counterpart to [stripSecrets]: without this, saving drops secrets —
     * they end up neither in the blob nor in encrypted storage). Empty
     * values leave the stored secret untouched so a blank draft field can't
     * wipe a previously saved secret.
     */
    private suspend fun persistChannelSecrets(config: ChannelConfig) {
        val typed = try {
            ChannelRegistry.buildChannelSettings(config.type, config.settingsJson)
        } catch (_: Exception) {
            return
        }
        for (field in typed.secretFields) {
            val value = config.settingsJson[field]
            if (value is String && value.isNotEmpty()) {
                secrets.write(secretKey(config.id, field), value)
            }
        }
    }

    /**
     * Deletes secrets belonging to channel accounts that no longer exist in
     * [next] (deleted via Settings). Runs at save time — rather than at
     * delete-tap time — so backing out without saving can't strand a stored
     * config without its secret. Deleting unknown keys is a no-op, so the
     * union over all known secret fields is safe.
     */
    private suspend fun pruneRemovedChannelSecrets(nextIds: Set<String>) {
        val raw = dataStore.data.first()[KEY] ?: return
        // Raw blob ids — not tryParse, whose placeholder filter would hide
        // the very ids being pruned.
        val removed = rawChannelIds(raw) - nextIds
        if (removed.isEmpty()) return
        val secretFields = ChannelRegistry.factories.keys.flatMapTo(mutableSetOf()) { type ->
            runCatching { ChannelRegistry.buildChannelSettings(type, emptyMap()) }
                .getOrNull()?.secretFields ?: emptyList()
        }
        for (id in removed) {
            for (field in secretFields) {
                secrets.delete(secretKey(id, field))
            }
        }
    }

    /** Channel ids straight from the persisted blob (no placeholder filtering). */
    private fun rawChannelIds(raw: String): Set<String> = try {
        val ids = mutableSetOf<String>()
        val arr = JSONObject(raw).optJSONArray("channelConfigs") ?: return emptySet()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let(ids::add)
        }
        ids
    } catch (_: Exception) {
        emptySet()
    }
    private fun stripSecrets(config: ChannelConfig): Map<String, Any?> = try {
        val typed = ChannelRegistry.buildChannelSettings(config.type, config.settingsJson)
        if (typed.secretFields.isEmpty()) {
            config.settingsJson
        } else {
            config.settingsJson.filterKeys { it !in typed.secretFields }
        }
    } catch (_: Exception) {
        // Unknown type (forward-version data): the typed secret list is
        // unavailable, so fail closed on secret-shaped keys rather than
        // persisting a possible inline credential in plaintext.
        android.util.Log.w(
            "SettingsStore",
            "stripping suspect keys for unknown channel type ${config.type}",
        )
        config.settingsJson.filterKeys { key ->
            SUSPECT_SECRET_SUBSTRINGS.none { key.contains(it, ignoreCase = true) }
        }
    }

    companion object {
        const val FILE_NAME = "settings"
        val KEY: Preferences.Key<String> = stringPreferencesKey("app_settings_v1")

        /** Backup key holding the last corrupt blob seen by [tryParse]. */
        val CORRUPT_BACKUP_KEY: Preferences.Key<String> =
            stringPreferencesKey("app_settings_corrupt_backup")

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

        /** Key fragments treated as credentials when the channel type is unknown. */
        private val SUSPECT_SECRET_SUBSTRINGS = listOf(
            "password", "passwd", "secret", "token", "apikey", "passphrase", "privatekey",
        )

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