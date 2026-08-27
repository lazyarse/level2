package io.securitycam.level2.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Single source of truth for detector type metadata (key, display label, icon, hint).
 * UI layers use this; the detection/storage layers continue using [TriggerType] string constants.
 */
enum class DetectorType(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val hint: String,
) {
    Motion(TriggerType.motion, "Motion", Icons.Filled.DirectionsRun, "Detects pixel-level changes between frames."),
    BabyCry(TriggerType.babyCry, "Baby cry", Icons.Filled.ChildCare, "Listens for a baby crying or fussing."),
    GlassBreak(TriggerType.glassBreak, "Glass break", Icons.Filled.BrokenImage, "Listens for the sound of breaking glass."),
    LoudNoise(TriggerType.loudNoise, "Loud noise", Icons.Filled.VolumeUp, "Triggers when audio exceeds the loud-noise threshold."),
    Person(TriggerType.person, "Person", Icons.Filled.Person, "Detects a person in the camera view."),
    Face(TriggerType.face, "Face", Icons.Filled.Face, "Detects any face in the camera view."),
    FaceKnown(TriggerType.faceKnown, "Known face", Icons.Filled.Face, "Detects a known face in the camera view."),
    FaceUnknown(TriggerType.faceUnknown, "Unknown face", Icons.Filled.SentimentDissatisfied, "Detects an unknown face in the camera view."),
    Tamper(TriggerType.tamper, "Tamper", Icons.Filled.VideocamOff, "Detects camera covered, moved or obstructed."),
    Health(TriggerType.health, "Heartbeat", Icons.Filled.MonitorHeart, "Monitors the app's internal heartbeat signal."),
    Dog(TriggerType.dog, "Dog", Icons.Filled.Pets, "Triggers on sight or sound (barking, growling)."),
    Cat(TriggerType.cat, "Cat", Icons.Filled.Pets, "Triggers on sight or sound (meowing, purring, hissing)."),
    Vehicle(TriggerType.vehicle, "Vehicle", Icons.Filled.DirectionsCar, "Detects a car or truck in the camera view."),
    Bird(TriggerType.bird, "Bird", Icons.Filled.Pets, "Detects birds."),
    Livestock(TriggerType.livestock, "Livestock", Icons.Filled.Pets, "Detects cows, sheep and horses."),
    Loitering(TriggerType.loitering, "Loitering", Icons.Filled.Timer, "Triggers when a person lingers longer than the dwell time."),
    Merged(TriggerType.merged, "Multiple triggers", Icons.Filled.NotificationImportant, "Groups multiple triggers from the same event."),
    Tripwire(TriggerType.tripwire, "Tripwire", Icons.Filled.NearMe, "Triggers when a person crosses a tripwire boundary.");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String) = byKey[key]
    }
}
