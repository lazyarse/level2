package io.securitycam.level1.core

import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.detection.DetectorRegistry
import io.securitycam.level1.detection.HybridDetector
import io.securitycam.level1.detection.MotionDetector
import io.securitycam.level1.event.triggerLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `test/detector_registry_test.dart` (Phase 2 subset). */
class DetectorRegistryTest {

    @Test
    fun registryBuildsAMotionDetectorForTheMotionTrigger() {
        val factory = DetectorRegistry.factoryFor(TriggerType.motion)
        assertNotNull(factory)
        val detector = factory!!(DetectorConfig(type = TriggerType.motion))
        assertTrue(detector is MotionDetector)
        assertEquals(TriggerType.motion, detector.triggerType)
        assertEquals("motion", detector.id)
    }

    @Test
    fun registryBuildsAudioDetectors() {
        for (type in listOf(TriggerType.babyCry, TriggerType.glassBreak, TriggerType.loudNoise)) {
            val detector = DetectorRegistry.factoryFor(type)!!(DetectorConfig(type = type))
            assertEquals(type, detector.triggerType)
            assertEquals(type, detector.id)
        }
    }

    @Test
    fun combinedPetDetectorsAreRegistered() {
        // Construction requires the YOLO context holder (device-only); here we
        // just verify registration — hybrid behavior is covered by
        // PetHybridDetectorTest with mock engines.
        for (type in listOf(TriggerType.dog, TriggerType.cat)) {
            assertNotNull(DetectorRegistry.factoryFor(type))
            assertTrue(DetectorRegistry.supports(type))
        }
    }

    @Test
    fun triggerLabelRendersMotion() {
        assertEquals("Motion", triggerLabel(TriggerType.motion))
    }

    @Test
    fun triggerLabelMapsFace() {
        assertEquals("Face", triggerLabel(TriggerType.face))
    }
}