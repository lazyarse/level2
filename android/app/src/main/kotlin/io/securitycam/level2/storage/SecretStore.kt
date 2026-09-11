package io.securitycam.level2.storage

import android.content.Context
import android.util.Log
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
        // commit(), not apply(): save() strips the blob on the assumption the
        // secret landed — an un-awaited write could leave it in neither place.
        // Failures propagate (never log the value) so the caller can abort.
        val ok = try {
            prefs().edit().putString(key, value).commit()
        } catch (t: Throwable) {
            Log.w(TAG, "secret write failed for $key", t)
            false
        }
        check(ok) { "secret write failed for $key" }
    }

    override suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        val ok = try {
            prefs().edit().remove(key).commit()
        } catch (t: Throwable) {
            Log.w(TAG, "secret delete failed for $key", t)
            false
        }
        check(ok) { "secret delete failed for $key" }
    }

    companion object {
        private const val TAG = "SecretStore"
    }
}