package io.securitycam.level2.storage

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Key/value store for channel secrets (bot tokens, SMTP passwords, …). */
interface SecretStore {
    suspend fun read(key: String): String?

    suspend fun write(key: String, value: String)

    suspend fun delete(key: String)
}

/** Test store keeping secrets in a map. */
class InMemorySecretStore : SecretStore {
    private val map = ConcurrentHashMap<String, String>()

    override suspend fun read(key: String): String? = map[key]

    override suspend fun write(key: String, value: String) {
        map[key] = value
    }

    override suspend fun delete(key: String) {
        map.remove(key)
    }

    val all: Map<String, String> get() = map.toMap()
}

/**
 * Keystore-backed secrets via `security-crypto`'s EncryptedSharedPreferences
 * (Android Keystore master key). Keys follow `channel.<id>.<field>`.
 */
class EncryptedSecretStore(context: Context) : SecretStore {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    private suspend fun prefs(): android.content.SharedPreferences = prefs ?: withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs ?: run {
                val masterKey = androidx.security.crypto.MasterKey.Builder(appContext)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val created = androidx.security.crypto.EncryptedSharedPreferences.create(
                    appContext,
                    "level2_secrets",
                    masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                prefs = created
                created
            }
        }
    }

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        try {
            prefs().getString(key, null)
        } catch (t: Throwable) {
            Log.w(TAG, "secret read failed", t)
            null
        }
    }

    override suspend fun write(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        try {
            prefs().edit().putString(key, value).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "secret write failed", t)
        }
    }

    override suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        try {
            prefs().edit().remove(key).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "secret delete failed", t)
        }
    }

    companion object {
        private const val TAG = "SecretStore"
    }
}