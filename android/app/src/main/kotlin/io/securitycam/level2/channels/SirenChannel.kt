package io.securitycam.level2.channels

import android.media.AudioManager
import android.media.ToneGenerator
import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.ChannelSettings

class SirenChannelSettings(
    val durationSeconds: Int = 15,
    val volume: Float = 0.8f,
) : ChannelSettings() {
    override val type: String get() = "siren"
    override fun toJson(): Map<String, Any?> = mapOf(
        "durationSeconds" to durationSeconds,
        "volume" to volume,
    )
    override val secretFields: List<String> get() = emptyList()

    companion object {
        fun fromJson(json: Map<String, Any?>): SirenChannelSettings = SirenChannelSettings(
            durationSeconds = (json["durationSeconds"] as? Number)?.toInt() ?: 15,
            volume = (json["volume"] as? Number)?.toFloat() ?: 0.8f,
        )
    }
}

class SirenChannel(
    override val id: String,
    override val enabled: Boolean = true,
    override val settings: SirenChannelSettings,
) : io.securitycam.level2.core.Channel {

    private var toneGenerator: ToneGenerator? = null

    override val type: String get() = "siren"

    override suspend fun send(message: AlertMessage) {
        play(settings.durationSeconds)
    }

    override suspend fun sendTest() {
        play(3)
    }

    override fun validate(): String? = null

    private fun play(durationSeconds: Int) {
        stop()
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, (settings.volume * 100).toInt())
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, durationSeconds * 1000)
            toneGenerator = tg
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stop()
            }, durationSeconds * 1000L)
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null
    }
}
