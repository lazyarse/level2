package io.securitycam.level1.detection

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
)