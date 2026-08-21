package io.securitycam.level1.channels

import io.securitycam.level1.core.AlertMessage
import java.time.Instant

/** Minimal JSON object encoder for fixed string-valued payloads (JVM-safe). */
internal fun jsonEncode(vararg fields: Pair<String, Any?>): String =
    fields.joinToString(",", "{", "}") { (k, v) ->
        when (v) {
            null -> "\"$k\":null"
            is Number -> "\"$k\":$v"
            is Boolean -> "\"$k\":$v"
            else -> "\"$k\":${jsonEscape(v.toString())}"
        }
    }

internal fun jsonEscape(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.append('"').toString()
}

/** In-memory log channel used as the always-on delivery fallback. */
class LogChannel(
    override val id: String = "log",
    override val enabled: Boolean = true,
) : io.securitycam.level1.core.Channel {

    val sent = mutableListOf<AlertMessage>()

    override val type: String get() = "log"

    override val settings: io.securitycam.level1.core.ChannelSettings = LogChannelSettings

    override suspend fun send(message: AlertMessage) {
        sent.add(message)
    }

    override suspend fun sendTest() {
        sent.add(
            AlertMessage(
                timestamp = Instant.now(),
                triggerType = "test",
                text = "Test alert from $id",
            ),
        )
    }

    override fun validate(): String? = null
}

object LogChannelSettings : io.securitycam.level1.core.ChannelSettings() {
    override val type: String get() = "log"
    override fun toJson(): Map<String, Any?> = emptyMap()
    override val secretFields: List<String> get() = emptyList()
}