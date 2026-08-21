package io.securitycam.level1.sensors

import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.PI

enum class AudioScene { silence, babyCry, glassBreak, bang }

/**
 * Deterministic synthetic audio windows for tests and the simulated source
 * (port of `lib/sensors/simulated_audio_source.dart`). The streaming
 * AudioSource wrapper lands with the Phase 4 monitoring wiring.
 */
object SimulatedAudioSource {
    const val sampleRate = 16000
    const val windowSamples = 15600

    fun generateWindow(scene: AudioScene): FloatArray {
        val rng = java.util.Random(42)
        val samples = FloatArray(windowSamples)
        when (scene) {
            AudioScene.silence -> {
                for (i in 0 until windowSamples) {
                    samples[i] = ((rng.nextDouble() - 0.5) * 0.02).toFloat()
                }
            }
            AudioScene.babyCry -> {
                val freq = 250.0
                for (i in 0 until windowSamples) {
                    val t = i.toDouble() / sampleRate
                    val mod = 0.7 + 0.3 * sin(2 * PI * 4 * t)
                    samples[i] = (0.45 * mod * sin(2 * PI * freq * t)).toFloat()
                }
            }
            AudioScene.glassBreak -> {
                for (i in 0 until windowSamples) {
                    val envelope = exp(-1.0 * (i.toDouble() / windowSamples))
                    samples[i] = (0.8 * envelope * (rng.nextDouble() * 2 - 1)).toFloat()
                }
            }
            AudioScene.bang -> {
                for (i in 0 until windowSamples) {
                    samples[i] = (rng.nextDouble() * 2 - 1).toFloat()
                }
            }
        }
        return samples
    }
}