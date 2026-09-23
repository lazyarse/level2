package io.securitycam.level2.detection

import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.core.TriggerType

/** Factory for a detector from its config. */
typealias DetectorFactory = (DetectorConfig) -> Detector

/**
 * Registry of detector factories keyed by trigger type. Audio detectors and
 * motion are available in Phase 2; face/person arrive in Phase 3 and
 * tamper/dog in Phase 6.
 *
 * Instances are independent: every holder ([MonitoringRuntime], pipelines,
 * tests) builds its own copy via [withDefaults] and mutates only that copy,
 * so overlapping runtimes can never clobber each other's factories.
 */
class DetectorRegistry private constructor(
    private val factories: MutableMap<String, DetectorFactory>,
) {
    fun register(type: String, factory: DetectorFactory) {
        factories[type] = factory
    }

    /** Removes a per-runtime override (see [MonitoringRuntime.stop]). */
    fun unregister(type: String) {
        factories.remove(type)
    }

    fun factoryFor(type: String): DetectorFactory? = factories[type]

    val types: Set<String> get() = factories.keys

    /** True when a detector class exists for [type] (used by settings parity). */
    fun supports(type: String): Boolean = factories.containsKey(type)

    companion object {
        private fun defaultFactories(): MutableMap<String, DetectorFactory> = linkedMapOf(
            TriggerType.motion to { c: DetectorConfig -> MotionDetector(c) },
            TriggerType.babyCry to { c: DetectorConfig ->
                io.securitycam.level2.detection.audio.BabyCryDetector(c)
            },
            TriggerType.glassBreak to { c: DetectorConfig ->
                io.securitycam.level2.detection.audio.GlassBreakDetector(c)
            },
            TriggerType.loudNoise to { c: DetectorConfig ->
                io.securitycam.level2.detection.audio.LoudNoiseDetector(c)
            },
            TriggerType.person to { c: DetectorConfig ->
                io.securitycam.level2.detection.person.PersonDetector(c)
            },
            TriggerType.face to { c: DetectorConfig ->
                io.securitycam.level2.detection.face.FaceDetector(c)
            },
            TriggerType.tamper to { c: DetectorConfig ->
                TamperDetector(c)
            },
            TriggerType.dog to { c: DetectorConfig ->
                io.securitycam.level2.detection.person.DogDetector(c)
            },
            TriggerType.cat to { c: DetectorConfig ->
                io.securitycam.level2.detection.person.CatDetector(c)
            },
            TriggerType.vehicle to { c: DetectorConfig ->
                io.securitycam.level2.detection.person.VehicleDetector(c)
            },
            TriggerType.bird to { c: DetectorConfig ->
                io.securitycam.level2.detection.person.BirdDetector(c)
            },
            TriggerType.livestock to { c: DetectorConfig ->
                io.securitycam.level2.detection.person.LivestockDetector(c)
            },
            TriggerType.loitering to { c: DetectorConfig ->
                io.securitycam.level2.detection.person.LoiteringDetector(c)
            },
            TriggerType.tripwire to { c: DetectorConfig ->
                io.securitycam.level2.detection.TripwireDetector(c)
            },
        )

        /** A fresh registry seeded with the shipped detector factories. */
        fun withDefaults(): DetectorRegistry = DetectorRegistry(defaultFactories())
    }
}