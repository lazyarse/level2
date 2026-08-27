package io.securitycam.level2.core

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
    const val faceKnown = "face_known"
    const val faceUnknown = "face_unknown"
    const val tamper = "tamper"
    const val health = "health"
    const val dogBark = "dog_bark"
    const val growl = "growl"
    const val catMeow = "cat_meow"
    const val cat = "cat"
    const val dog = "dog"
    const val vehicle = "vehicle"
    const val bird = "bird"
    const val livestock = "livestock"
    const val loitering = "loitering"
    const val siren = "siren"
    const val tripwire = "tripwire"
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