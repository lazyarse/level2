package io.securitycam.level1.identity

import io.securitycam.level1.core.KnownFace

/**
 * Process-wide snapshot of enrolled people so recognition always sees the
 * current roster without re-reading settings per frame. SettingsViewModel
 * updates it on every enroll/delete/save; MonitoringRuntime seeds it at
 * creation and reads it per recognition pass via [people].
 */
object FaceDirectory {

    @Volatile
    private var people: List<KnownFace> = emptyList()

    fun setAll(updated: List<KnownFace>) {
        people = updated
    }

    fun people(): List<KnownFace> = people
}
