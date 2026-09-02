package io.securitycam.level2.detection.person

import android.content.Context
import io.securitycam.level2.detection.DetectedBox

/** Abstraction over an on-device person detector (port of `person_engine.dart`). */
interface PersonEngine {
    suspend fun init()

    /** Returns detected people in [frame]'s color bitmap. Empty list = no people. */
    suspend fun detectPersons(frame: io.securitycam.level2.detection.ColorBitmap): List<DetectedBox>

    suspend fun dispose()
}

/** Test/dry-run engine: returns whatever [persons] was pre-loaded with. */
class MockPersonEngine : PersonEngine {
    val persons = mutableListOf<DetectedBox>()

    override suspend fun init() {}

    override suspend fun detectPersons(frame: io.securitycam.level2.detection.ColorBitmap): List<DetectedBox> =
        persons.toList()

    override suspend fun dispose() {}
}

/** Holds the application context so detector factories can build engines lazily. */
object AppContextHolder {
    @Volatile
    var context: Context? = null

    fun require(): Context = checkNotNull(context) { "AppContextHolder not initialized" }
}