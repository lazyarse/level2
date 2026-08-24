package io.securitycam.level1.sensors

import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.PI

enum class AudioScene { silence, babyCry, glassBreak, bang, dogBark, growl }

/**
 * Deterministic synthetic audio windows — test fixture for the audio
 * detectors (the Flutter-era desktop "simulated source" was dropped with
 * the Phase 7 cutover; Android always uses the microphone).
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
            AudioScene.dogBark -> {
                val freq = 400.0
                for (i in 0 until windowSamples) {
                    val t = i.toDouble() / sampleRate
                    val burst = if ((t * 3).toInt() % 2 == 0) 1.0 else 0.2
                    val mod = 0.6 + 0.4 * sin(2 * PI * 8 * t)
                    samples[i] = (0.5 * burst * mod * sin(2 * PI * freq * t)).toFloat()
                }
            }
            AudioScene.growl -> {
                val freq = 120.0
                for (i in 0 until windowSamples) {
                    val t = i.toDouble() / sampleRate
                    val wobble = 0.7 + 0.3 * sin(2 * PI * 2 * t)
                    samples[i] = (0.4 * wobble * sin(2 * PI * freq * t)).toFloat()
                }
            }
        }
        return samples
    }
}