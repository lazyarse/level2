package io.securitycam.level2.detection

/** A detected object: bounding box (top-left x/y, bottom-right x/y) + confidence score. */
data class DetectedBox(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val score: Double)
