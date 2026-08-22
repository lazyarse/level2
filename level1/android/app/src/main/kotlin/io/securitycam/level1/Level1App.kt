package io.securitycam.level1

import android.app.Application
import io.securitycam.level1.camera_service.VideoClipRecorder

/**
 * Application entry point: binds the app context into process-wide holders so
 * playback/retention helpers work before the first monitoring session.
 */
class Level1App : Application() {
    override fun onCreate() {
        super.onCreate()
        VideoClipRecorder.attach(this)
    }
}
