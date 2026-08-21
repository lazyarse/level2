package io.securitycam.level1.core

import java.time.Instant

/** Trigger type id constants shared across detection, pipeline and events. */
object TriggerType {
    const val motion = "motion"
    const val babyCry = "baby_cry"
    const val glassBreak = "glass_break"
    const val loudNoise = "loud_noise"
    const val merged = "merged"
    const val person = "person"
    const val face = "face"
    const val tamper = "tamper"
}

/** A single detector firing, before batching (port of `lib/core/models.dart`). */
data class TriggerEvent(
    val timestamp: Instant,
    val triggerType: String,
    val score: Double,
    val detectorId: String,
    /** Optional qualifier, e.g. tamper's "covered"/"moved". */
    val detail: String? = null,
)