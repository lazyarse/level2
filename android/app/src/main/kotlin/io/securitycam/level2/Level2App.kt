package io.securitycam.level2

import android.app.Application
import androidx.work.Configuration
import io.securitycam.level2.camera_service.VideoClipRecorder
import io.securitycam.level2.core.ConnectivityMonitor
import io.securitycam.level2.work.OutboxWorker

/**
 * Application entry point: binds the app context into process-wide holders so
 * playback/retention helpers work before the first monitoring session, and
 * arms the offline-outbox drain for whenever connectivity returns.
 *
 * Implements [Configuration.Provider] so WorkManager initializes lazily on
 * first use — including under Robolectric, where the startup-provider path is
 * unavailable.
 */
class Level2App : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        VideoClipRecorder.attach(this)
        ConnectivityMonitor.start(this)
        OutboxWorker.schedule(this)
    }
}
