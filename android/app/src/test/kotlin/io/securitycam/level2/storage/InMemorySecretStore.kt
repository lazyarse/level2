package io.securitycam.level2.storage

import java.util.concurrent.ConcurrentHashMap

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
