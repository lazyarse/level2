package io.securitycam.level1.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Single source of truth for detector type metadata (key, display label, icon).
 * UI layers use this; the detection/storage layers continue using [TriggerType] string constants.
 */
enum class DetectorType(
    val key: String,
    val label: String,
    val icon: ImageVector,
) {
    Motion(TriggerType.motion, "Motion", Icons.Filled.DirectionsRun),
    BabyCry(TriggerType.babyCry, "Baby cry", Icons.Filled.ChildCare),
    GlassBreak(TriggerType.glassBreak, "Glass break", Icons.Filled.BrokenImage),
    LoudNoise(TriggerType.loudNoise, "Loud noise", Icons.Filled.VolumeUp),
    Person(TriggerType.person, "Person", Icons.Filled.Person),
    Face(TriggerType.face, "Face", Icons.Filled.Face),
    FaceKnown(TriggerType.faceKnown, "Known face", Icons.Filled.Face),
    FaceUnknown(TriggerType.faceUnknown, "Unknown face", Icons.Filled.SentimentDissatisfied),
    Tamper(TriggerType.tamper, "Tamper", Icons.Filled.VideocamOff),
    Health(TriggerType.health, "Health", Icons.Filled.HealthAndSafety),
    DogBark(TriggerType.dogBark, "Dog bark", Icons.Filled.Pets),
    Growl(TriggerType.growl, "Growl", Icons.Filled.VolumeDown),
    CatMeow(TriggerType.catMeow, "Cat vocalisation", Icons.Filled.Pets),
    Dog(TriggerType.dog, "Dog", Icons.Filled.Pets),
    Cat(TriggerType.cat, "Cat", Icons.Filled.Pets),
    Vehicle(TriggerType.vehicle, "Vehicle", Icons.Filled.DirectionsCar),
    Animal(TriggerType.animal, "Animal", Icons.Filled.Pets),
    Loitering(TriggerType.loitering, "Loitering", Icons.Filled.Timer),
    Merged(TriggerType.merged, "Multiple triggers", Icons.Filled.NotificationImportant);

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String) = byKey[key]
    }
}
