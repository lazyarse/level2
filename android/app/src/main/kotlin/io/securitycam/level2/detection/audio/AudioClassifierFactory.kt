package io.securitycam.level2.detection.audio

import android.content.Context

/**
 * Audio classifier factory.
 * Uses the real YAMNet model when it loads; degrades to the mock classifier so
 * monitoring keeps working without the model.
 */
object AudioClassifierFactory {
    suspend fun build(context: Context): AudioEventClassifier =
        YamnetClassifier.load(context) ?: MockAudioEventClassifier()
}