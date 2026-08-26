package io.securitycam.level2

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
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
 *
 * Implements [CameraXConfig.Provider] to override the default
 * `availableCamerasLimiter` (`DEFAULT_BACK_CAMERA`).  The goldfish camera HAL
 * used by the Android emulator doesn't set `LENS_FACING`, so the default
 * selector filters it out before CameraX's repository is populated.  Using an
 * unfiltered selector (`CameraSelector.Builder().build()`) ensures the goldfish
 * camera is included, allowing `bindToLifecycle()` to succeed with our custom
 * camera-ID-based selector in [MonitoringService.cameraSelectorFor].
 */
class Level2App : Application(), CameraXConfig.Provider, Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /**
     * Lenient CameraX configuration: accept all cameras regardless of
     * `LENS_FACING`.  The goldfish emulator camera (ID "10") reports
     * `LENS_FACING: null`, which causes the default `DEFAULT_BACK_CAMERA`
     * limiter to exclude it.  The config is built from
     * [Camera2Config.defaultConfig] via [CameraXConfig.Builder.fromConfig] to
     * preserve the mandatory Camera2 providers (CameraFactory,
     * DeviceSurfaceManager, UseCaseConfigFactory), then the limiter is
     * overridden with an unfiltered selector that matches every camera.
     */
    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.Builder().build())
            .build()

    override fun onCreate() {
        super.onCreate()
        VideoClipRecorder.attach(this)
        ConnectivityMonitor.start(this)
        OutboxWorker.schedule(this)
    }
}
