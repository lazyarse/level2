package io.securitycam.level2.identity

import io.securitycam.level2.core.KnownFace

/**
 * Process-wide snapshot of enrolled people feeding the settings/enrollment UI
 * path without re-reading settings per frame. SettingsViewModel updates it on
 * every enroll/delete/save.
 *
 * Wave 4: live monitoring no longer reads this — MonitoringRuntime snapshots
 * [io.securitycam.level2.core.AppSettings.knownFaces] at creation and clears
 * the snapshot on stop — so overlapping runtimes keep independent rosters and
 * enrollments made mid-session take effect on restart.
 */
object FaceDirectory {

    @Volatile
    private var people: List<KnownFace> = emptyList()

    fun setAll(updated: List<KnownFace>) {
        people = updated
    }

    fun people(): List<KnownFace> = people
}
