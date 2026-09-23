package io.securitycam.level2.core

import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.core.AppSettings.Companion.withDetectorConfig
import io.securitycam.level2.core.AppSettings.Companion.withFaceRecognition
import io.securitycam.level2.event.triggerLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Face-recognition trigger labels and settings migration. */
class FaceRecognitionSettingsTest {

    @Test
    fun recognitionTriggerLabels() {
        assertEquals("Known face", triggerLabel(TriggerType.faceKnown))
        assertEquals("Unknown face", triggerLabel(TriggerType.faceUnknown))
    }

    @Test
    fun disabledByDefault() {
        assertFalse(AppSettings.faceRecognitionEnabled(AppSettings.defaults()))
    }

    @Test
    fun enablingSeedsRoutingConfigsAndKeepsFaceDetector() {
        val on = AppSettings.defaults().withFaceRecognition(true)
        assertTrue(AppSettings.faceRecognitionEnabled(on))
        val face = on.detectorConfigs[TriggerType.face]!!
        assertTrue(face.enabled)
        for (type in listOf(TriggerType.faceKnown, TriggerType.faceUnknown)) {
            val c = on.detectorConfigs[type]!!
            assertEquals(1, c.persistenceFrames)
        }
        assertEquals(
            AppSettings.FACE_MATCH_THRESHOLD,
            on.detectorConfigs[TriggerType.faceKnown]!!.threshold,
            1e-9,
        )
    }

    @Test
    fun enablingWhenNoFaceConfigSeedsDefaults() {
        val stripped = AppSettings.defaults().copyWith().let {
            it.copy(detectorConfigs = it.detectorConfigs - TriggerType.face)
        }
        val on = stripped.withFaceRecognition(true)
        assertTrue(on.detectorConfigs.containsKey(TriggerType.face))
        assertTrue(AppSettings.faceRecognitionEnabled(on))
    }

    @Test
    fun disablingRemovesRoutingConfigsOnly() {
        val off = AppSettings.defaults().withFaceRecognition(true).withFaceRecognition(false)
        assertFalse(AppSettings.faceRecognitionEnabled(off))
        assertTrue(off.detectorConfigs.containsKey(TriggerType.face))
        // Everything else untouched vs defaults.
        assertEquals(
            AppSettings.defaults().detectorConfigs.keys - setOf(TriggerType.face),
            off.detectorConfigs.keys - setOf(TriggerType.face),
        )
    }

    @Test
    fun migrationIsIdempotentBothWays() {
        val defaults = AppSettings.defaults()
        assertEquals(defaults, defaults.withFaceRecognition(false))
        val on = defaults.withFaceRecognition(true)
        assertEquals(on, on.withFaceRecognition(true))
    }

    @Test
    fun roundTripPreservesRecognitionConfigs() {
        val on = AppSettings.defaults().withFaceRecognition(true)
        val back = AppSettings.fromJson(on.toJson())
        assertTrue(AppSettings.faceRecognitionEnabled(back))
        assertEquals(
            on.detectorConfigs[TriggerType.faceKnown],
            back.detectorConfigs[TriggerType.faceKnown],
        )
    }

    @Test
    fun disablingFaceDetectorForcesRecognitionOff() {
        val on = AppSettings.defaults().withFaceRecognition(true)
        assertTrue(AppSettings.faceRecognitionEnabled(on))
        val off = on.withDetectorConfig(
            on.detectorConfigs.getValue(TriggerType.face).copy(enabled = false),
        )
        assertFalse(AppSettings.faceRecognitionEnabled(off))
        assertFalse(off.detectorConfigs.getValue(TriggerType.face).enabled)
        // Enrolled faces are untouched — only the routing configs go.
        assertEquals(on.knownFaces, off.knownFaces)
    }

    @Test
    fun disablingNonFaceDetectorLeavesRecognitionAlone() {
        val on = AppSettings.defaults().withFaceRecognition(true)
        val person = on.detectorConfigs.getValue(TriggerType.person)
        val next = on.withDetectorConfig(person.copy(enabled = !person.enabled))
        assertEquals(
            AppSettings.faceRecognitionEnabled(on),
            AppSettings.faceRecognitionEnabled(next),
        )
    }

    @Test
    fun fromJsonHealsRecognitionWhenFaceDetectorDisabled() {
        // Legacy divergent state: recognition on, face detector off. Built by
        // direct map edit — withDetectorConfig would already force it off.
        val on = AppSettings.defaults().withFaceRecognition(true)
        val divergent = on.copy(
            detectorConfigs = on.detectorConfigs +
                (TriggerType.face to on.detectorConfigs.getValue(TriggerType.face)
                    .copy(enabled = false)),
        )
        assertTrue(AppSettings.faceRecognitionEnabled(divergent))
        val healed = AppSettings.fromJson(divergent.toJson())
        assertFalse(AppSettings.faceRecognitionEnabled(healed))
        assertFalse(healed.detectorConfigs.getValue(TriggerType.face).enabled)
    }
}
