package io.securitycam.level2.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val SAFE_CHARS = Regex("[^A-Za-z0-9._-]")
private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")

/**
 * Shared media filename scheme for snapshots and video clips:
 * `2026-08-18_10-30-00-123_Hallway.jpg` (colon-free, millisecond suffix for
 * uniqueness, camera name sanitized). Mirrors `lib/core/media_naming.dart`.
 */
fun mediaFileName(
    timestamp: LocalDateTime,
    cameraName: String,
    extension: String,
): String {
    val safe = SAFE_CHARS.replace(cameraName, "_")
    return "${NAME_FORMAT.format(timestamp)}_$safe.$extension"
}