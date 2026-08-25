package io.securitycam.level2.detection

import java.time.Instant

/** A labeled detection (port of `lib/core/models.dart` `Detection`). */
data class Detection(
    val label: String,
    val score: Double,
)

/** Result of a detector analyzing one frame or score set. */
data class DetectionResult(
    val timestamp: Instant,
    val triggerType: String,
    val score: Double,
    val triggered: Boolean,
    val detections: List<Detection> = emptyList(),
    /** Optional qualifier, e.g. tamper's "covered"/"moved". */
    val detail: String? = null,
    /** Overrides the emitting detector's id in the TriggerEvent (routing). */
    val detectorId: String? = null,
)