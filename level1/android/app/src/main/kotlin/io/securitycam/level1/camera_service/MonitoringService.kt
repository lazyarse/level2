package io.securitycam.level1.camera_service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import io.securitycam.level1.MainActivity
import io.securitycam.level1.core.LiveViewSettings
import io.securitycam.level1.storage.EncryptedSecretStore
import io.securitycam.level1.storage.SettingsStore
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service owning the CameraX session so the camera keeps streaming
 * with the screen off / Activity stopped. Analysis frames (preset resolution
 * BGR, ~4 fps) are published on [CameraFrameBus]; stills are captured on demand;
 * the live preview surface is supplied by the UI via [setPreviewSurfaceProvider].
 */
class MonitoringService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // START_STICKY restart delivers a null intent with no saved extras;
        // restarting with defaults would silently misconfigure monitoring, so
        // stop instead and let the UI drive a fresh, explicit start.
        if (intent == null) {
            Log.w(TAG, "sticky restart without extras; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.getBooleanExtra(EXTRA_PREVIEW_ONLY, false)) {
            MonitoringServiceController.startPreviewOnly(
                this,
                intent.getStringExtra(EXTRA_CAMERA_ID) ?: "0",
            )
        } else {
            MonitoringServiceController.onStart(
                this,
                intent.getStringExtra(EXTRA_CAMERA_ID) ?: "0",
                intent.getStringExtra(EXTRA_CAMERA_NAME) ?: "Hallway",
                intent.getIntExtra(EXTRA_PRE_ROLL, 5) ?: 5,
                intent.getIntExtra(EXTRA_POST_ROLL, 5) ?: 5,
                intent.getBooleanExtra(EXTRA_RECORD_VIDEO, true) ?: true,
                intent.getStringExtra(EXTRA_VIDEO_QUALITY) ?: "lowest",
                intent.getIntExtra(EXTRA_ANALYSIS_WIDTH, 320) ?: 320,
                intent.getIntExtra(EXTRA_ANALYSIS_HEIGHT, 240) ?: 240,
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        MonitoringServiceController.onServiceDestroyed(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitoringService"

        const val EXTRA_CAMERA_ID = "cameraId"
        const val EXTRA_CAMERA_NAME = "cameraName"
        const val EXTRA_PRE_ROLL = "preRollSeconds"
        const val EXTRA_POST_ROLL = "postRollSeconds"
        const val EXTRA_RECORD_VIDEO = "recordVideo"
        const val EXTRA_VIDEO_QUALITY = "videoQuality"
        const val EXTRA_ANALYSIS_WIDTH = "analysisWidth"
        const val EXTRA_ANALYSIS_HEIGHT = "analysisHeight"
        const val EXTRA_PREVIEW_ONLY = "previewOnly"

        /** Gate before dispatching: a denied grant must never start an FGS that
         * is then obligated to reach startForeground() within ~5 s. */
        private fun hasCameraPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        fun start(
            context: Context,
            cameraId: String,
            cameraName: String,
            preRollSeconds: Int,
            postRollSeconds: Int,
            recordVideo: Boolean,
            videoQuality: String,
            analysisWidth: Int = 320,
            analysisHeight: Int = 240,
        ) {
            if (!hasCameraPermission(context)) return
            val intent = Intent(context, MonitoringService::class.java)
                .putExtra(EXTRA_CAMERA_ID, cameraId)
                .putExtra(EXTRA_CAMERA_NAME, cameraName)
                .putExtra(EXTRA_PRE_ROLL, preRollSeconds)
                .putExtra(EXTRA_POST_ROLL, postRollSeconds)
                .putExtra(EXTRA_RECORD_VIDEO, recordVideo)
                .putExtra(EXTRA_VIDEO_QUALITY, videoQuality)
                .putExtra(EXTRA_ANALYSIS_WIDTH, analysisWidth)
                .putExtra(EXTRA_ANALYSIS_HEIGHT, analysisHeight)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startPreview(context: Context, cameraId: String) {
            if (!hasCameraPermission(context)) return
            val intent = Intent(context, MonitoringService::class.java)
                .putExtra(EXTRA_CAMERA_ID, cameraId)
                .putExtra(EXTRA_PREVIEW_ONLY, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/**
 * Owns the FGS lifecycle + CameraX bind. Kept out of [MonitoringService] so the
 * same controller is reachable from `MonitorViewModel` and the camera service.
 */
object MonitoringServiceController {
    private const val TAG = "CameraService"
    private const val CHANNEL_ID = "monitoring"
    private const val NOTIFICATION_ID = 1

    private val executor = Executors.newSingleThreadExecutor()
    private val micCapture = MicCapture()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var active = false

    /** True while the lightweight preview-only session owns the camera. */
    private var previewOnlyMode = false
    private var frameCount = 0L
    private var lastPublishMs = 0L
    private var recordVideo = true
    private var analysisWidth = 320
    private var analysisHeight = 240

    // Live View state
    private var liveViewEncoder: LiveViewEncoder? = null
    private var liveViewServer: LiveViewServer? = null
    private var liveViewPushClient: LiveViewPushClient? = null
    private var liveViewPacketizer: RtpPacketizer? = null
    private var liveViewActive = false

    // Preview use case bound into the CameraX group; its surface provider is
    // supplied/cleared by the UI via [setPreviewSurfaceProvider].
    private var boundPreview: Preview? = null
    private var pendingPreviewProvider: Preview.SurfaceProvider? = null

    /** Camera id of the last successful preview-only bind (flip de-dup). */
    private var boundPreviewCameraId: String? = null

    // Bound Camera handle for zoom (net-new Phase 1.4).
    private var boundCamera: Camera? = null
    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    fun onStart(
        service: LifecycleService,
        cameraId: String,
        cameraName: String,
        preRollSeconds: Int,
        postRollSeconds: Int,
        recordVideo: Boolean,
        videoQuality: String,
        analysisWidth: Int = 320,
        analysisHeight: Int = 240,
    ) {
        Log.i(TAG, "onStart cameraId=$cameraId")
        if (active) return
        previewOnlyMode = false
        // Permission gate before any foreground/wakelock side effects (design
        // doc gap 6). Exceptional here (starters pre-check), but if hit the FGS
        // obligation must still be satisfied: post the notification, then stop.
        if (ContextCompat.checkSelfPermission(service, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CAMERA permission not granted")
            startForeground(service)
            service.stopSelf()
            return
        }
        active = true
        frameCount = 0
        this.recordVideo = recordVideo
        this.analysisWidth = analysisWidth
        this.analysisHeight = analysisHeight
        startForeground(service)
        acquireWakeLock(service)
        VideoClipRecorder.configure(
            service, cameraName, preRollSeconds, postRollSeconds, videoQuality
        )
        if (ContextCompat.checkSelfPermission(service, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            // Native-owned mic: started before CameraX binds; PCM feeds both the
            // analysis stream and the clip recorder's audio buffer.
            micCapture.start { pcm, startSample ->
                CameraEvents.publishMicPcm(pcm, startSample)
                VideoClipRecorder.onMicPcm(pcm, startSample)
                liveViewEncoder?.feedAudioData(pcm, startSample)
            }
        } else {
            Log.w(TAG, "RECORD_AUDIO permission not granted; running video-only")
        }
        // Start Live View if enabled in settings
        startLiveView(service)
        bindCamera(service, cameraId)
    }

    fun onServiceDestroyed(service: Service) {
        active = false
        previewOnlyMode = false
        try {
            teardownCameraState()
        } finally {
            runCatching { micCapture.stop() }
            runCatching { VideoClipRecorder.onMonitoringStopped() }
            runCatching { stopLiveView() }
            releaseWakeLock()
            // Never let the singleton retain a destroyed service instance.
            activeService = null
        }
    }

    fun stop() {
        // CameraX requires the app main thread; the UI calls us from there but
        // instrumentation/tests may not, so hop over when needed (synchronously).
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopInternal()
        } else {
            val done = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(Looper.getMainLooper()).post {
                try {
                    stopInternal()
                } finally {
                    done.countDown()
                }
            }
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun stopInternal() {
        active = false
        try {
            teardownCameraState()
        } finally {
            // Exception-safe: a throwing unbind/mic/recorder must never leak
            // the wake lock or leave the foreground service running.
            runCatching { micCapture.stop() }
            runCatching { VideoClipRecorder.onMonitoringStopped() }
            runCatching { stopLiveView() }
            releaseWakeLock()
            val service = activeService
            if (service != null) {
                stopForegroundAndService(service)
            }
            activeService = null
        }
    }

    /** Unbinds CameraX and clears camera-related controller state. */
    private fun teardownCameraState() {
        cameraProvider?.unbindAll()
        imageAnalysis = null
        imageCapture = null
        boundPreview?.setSurfaceProvider(null)
        boundPreview = null
        boundCamera = null
        boundPreviewCameraId = null
        _zoomRatio.value = 1f
    }

    private fun stopForegroundAndService(service: Service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            service.stopForeground(true)
        }
        service.stopSelf()
    }

    // ---- Preview-only mode ----

    fun startPreviewOnly(
        service: LifecycleService,
        cameraId: String,
    ) {
        Log.i(TAG, "startPreviewOnly cameraId=$cameraId")
        if (active) return
        previewOnlyMode = true
        if (ContextCompat.checkSelfPermission(service, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CAMERA permission not granted")
            startForeground(service, preview = true)
            service.stopSelf()
            return
        }
        active = true
        startForeground(service, preview = true)
        bindPreviewOnly(service, cameraId)
    }

    fun stopPreviewOnly() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopPreviewOnlyInternal()
        } else {
            val done = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(Looper.getMainLooper()).post {
                try {
                    stopPreviewOnlyInternal()
                } finally {
                    done.countDown()
                }
            }
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun stopPreviewOnlyInternal() {
        active = false
        previewOnlyMode = false
        try {
            teardownCameraState()
        } finally {
            // No wake lock / mic / recorder / live view were acquired for
            // preview-only; just drop the foreground service.
            val service = activeService
            if (service != null) {
                stopForegroundAndService(service)
            }
            activeService = null
        }
    }

    /**
     * Rebinds the preview-only session to another camera in place (front/back
     * flip on the enrollment screen): keeps the foreground service and the
     * active flag, unbinds use cases, rebinds with the new selector. No-op
     * when preview-only doesn't own the session.
     */
    fun switchPreviewCamera(cameraId: String) {
        val service = activeService ?: return
        if (!previewOnlyMode) return
        if (boundPreviewCameraId == cameraId) return
        Log.i(TAG, "switchPreviewCamera cameraId=$cameraId")
        teardownCameraState()
        bindPreviewOnly(service, cameraId)
    }

    /**
     * Maps a persisted cameraId to a CameraX selector. Legacy keys keep their
     * meaning; any other id (e.g. "2", "3" from the settings dropdown) selects
     * the exact Camera2 camera so multi-camera choices actually bind instead
     * of silently falling back to the back camera.
     */
    private fun cameraSelectorFor(cameraId: String): CameraSelector = when (cameraId) {
        "front", "1" -> CameraSelector.DEFAULT_FRONT_CAMERA
        "back", "0" -> CameraSelector.DEFAULT_BACK_CAMERA
        else -> CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { info ->
                    androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId ==
                        cameraId
                }
            }
            .build()
    }

    private fun bindPreviewOnly(service: LifecycleService, cameraId: String) {
        val providerFuture = ProcessCameraProvider.getInstance(service)
        providerFuture.addListener({
            // Stop may have won the race while the provider future resolved;
            // never bind into a torn-down session.
            if (!active) return@addListener
            val provider = providerFuture.get()
            cameraProvider = provider
            val selector = cameraSelectorFor(cameraId)
            try {
                val rotation = displayRotation(service)
                val preview = Preview.Builder()
                    .setTargetRotation(rotation)
                    .build()
                    .also { p ->
                        boundPreview = p
                        pendingPreviewProvider?.let { p.setSurfaceProvider(it) }
                        Log.i(TAG, "previewOnly use case built rotation=$rotation")
                    }
                // Lightweight analysis feed so CameraFrameBus listeners (face
                // enrollment today, preview-time detectors later) receive
                // frames without the full monitoring pipeline.
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(analysisWidth, analysisHeight))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor) { image: ImageProxy ->
                    try {
                        val now = System.currentTimeMillis()
                        if (active && now - lastPublishMs >= 250L) {
                            lastPublishMs = now
                            CameraFrameBus.publish(toBgr(image), image.width, image.height)
                        }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    service, selector, preview, analysis
                ).also { camera ->
                    onCameraBound(camera)
                    boundPreviewCameraId = cameraId
                    Log.i(TAG, "previewOnly camera bound id=$cameraId")
                }
                CameraEvents.publishPreviewStatus(true)
            } catch (e: Exception) {
                Log.e(TAG, "previewOnly camera bind failed", e)
                boundPreview?.setSurfaceProvider(null)
                boundPreview = null
                CameraEvents.publishPreviewStatus(false)
            }
        }, ContextCompat.getMainExecutor(service))
    }

    // ---- End preview-only mode ----

    private var activeService: LifecycleService? = null

    /**
     * Attaches or detaches the live preview. Pass `null` on screen-off / screen
     * dispose to keep streaming analysis/video without rendering; the service
     * keeps the Preview use case bound either way. The provider is remembered
     * so a UI that attaches before the CameraX bind settles (or re-attaches
     * after a rebind) still lands on the bound preview.
     */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        pendingPreviewProvider = provider
        boundPreview?.setSurfaceProvider(provider)
    }

    /** Re-applies the display rotation to the bound preview (display change). */
    fun setTargetRotation(rotation: Int) {
        boundPreview?.targetRotation = rotation
    }

    /** Pins the newly-bound Camera; primes zoom range from its cameraInfo. */
    fun onCameraBound(camera: Camera) {
        boundCamera = camera
        currentZoomState(camera)?.let { _zoomRatio.value = it.zoomRatio }
    }

    private fun currentZoomState(camera: Camera): ZoomState? =
        camera.cameraInfo.zoomState.value

    /** Latest zoom ratio; drives the % badge and double-tap reset. */
    fun zoomRatio(): StateFlow<Float> = _zoomRatio

    /**
     * Applies a zoom ratio clamped to the camera's [minZoomRatio, maxZoomRatio]
     * range. Fire-and-forget on the controller executor; the ratio flow is
     * updated on success so the badge/gesture state stays honest.
     */
    fun setZoomRatio(ratio: Float) {
        val camera = boundCamera ?: return
        val state = currentZoomState(camera) ?: return
        val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        camera.cameraControl.setZoomRatio(clamped).addListener({
            _zoomRatio.value = clamped
        }, executor)
    }

    fun previewActive(): Boolean = boundPreview != null

    /** True while either monitoring or preview-only owns the camera. */
    fun cameraActive(): Boolean = active

    private fun displayRotation(context: Context): Int {
        // A background `LifecycleService` has no associated display, so
        // `context.display` throws on API 30+; use the default display instead.
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }
        return rotation ?: Surface.ROTATION_0
    }

    private fun startForeground(service: LifecycleService, preview: Boolean = false) {
        activeService = service
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Monitoring", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val contentIntent = PendingIntent.getActivity(
            service, 0, Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(if (preview) "Camera preview" else "Monitoring active")
            .setContentText(
                if (preview) "Tap to start monitoring"
                else "Camera analysis is running"
            )
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
        service.startForeground(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "level1:monitoring"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun startLiveView(context: Context) {
        try {
            // Keystore-backed settings load is blocking IO; keep it off the
            // main thread (we're called synchronously from onStartCommand).
            val lv = java.util.concurrent.CompletableFuture
                .supplyAsync {
                    kotlinx.coroutines.runBlocking {
                        SettingsStore(context, EncryptedSecretStore(context)).load().liveView
                    }
                }
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!lv.enabled) return

            val packetizer = RtpPacketizer()
            liveViewPacketizer = packetizer

            val encoder = LiveViewEncoder(object : LiveViewEncoderCallback {
                override fun onVideoData(nalUnit: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean) {
                    val packets = packetizer.packetize(nalUnit, presentationTimeUs, isKeyFrame)
                    for (p in packets) {
                        liveViewServer?.sendRtpPacket(p.data)
                    }
                }

                override fun onVideoConfig(sps: ByteArray, pps: ByteArray) {
                    packetizer.setParameterSets(sps, pps)
                    liveViewServer?.setParameterSets(sps, pps)
                }

                override fun onAudioData(data: ByteArray, presentationTimeUs: Long) {
                    liveViewServer?.sendRtpPacket(data)
                    liveViewPushClient?.onAudioFrame(data, presentationTimeUs)
                }
            })

            val (w, h) = resolutionToSize(lv.resolution)
            encoder.configure(w, h, lv.fps, 2_000_000, lv.audioEnabled)
            liveViewEncoder = encoder
            encoder.start()

            if (lv.mode == "server") {
                val server = LiveViewServer(
                    port = lv.port,
                    username = lv.username,
                    password = lv.password,
                    videoStream = { Log.i(TAG, "LiveView client connected") },
                    stopStream = { Log.i(TAG, "LiveView client disconnected") },
                    requestKeyFrame = { encoder.requestKeyFrame() },
                )
                liveViewServer = server
                server.start()
                Log.i(TAG, "LiveView server started on port ${lv.port}")
            } else if (lv.mode == "push" && lv.relayUrl.isNotEmpty()) {
                val pushClient = LiveViewPushClient(
                    relayUrl = lv.relayUrl,
                    username = lv.username,
                    password = lv.password,
                    packetizer = packetizer,
                )
                liveViewPushClient = pushClient
                pushClient.connect()
                Log.i(TAG, "LiveView push client connected to ${lv.relayUrl}")
            }
            liveViewActive = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Live View", e)
            stopLiveView()
        }
    }

    private fun stopLiveView() {
        liveViewActive = false
        try { liveViewServer?.stop() } catch (_: Exception) {}
        try { liveViewPushClient?.disconnect() } catch (_: Exception) {}
        try { liveViewEncoder?.stop() } catch (_: Exception) {}
        liveViewServer = null
        liveViewPushClient = null
        liveViewEncoder = null
        liveViewPacketizer = null
    }

    private fun resolutionToSize(resolution: String): Pair<Int, Int> = when (resolution) {
        "480p" -> 854 to 480
        "1080p" -> 1920 to 1080
        else -> 1280 to 720
    }

    private fun bindCamera(
        service: LifecycleService,
        cameraId: String,
        allowPreview: Boolean = true,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(service)
        providerFuture.addListener({
            // Stop may have won the race while the provider future resolved;
            // never bind into a torn-down session.
            if (!active) return@addListener
            val provider = providerFuture.get()
            cameraProvider = provider
            val selector = cameraSelectorFor(cameraId)
            try {
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(analysisWidth, analysisHeight))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor) { image: ImageProxy ->
                    // The buffer must be returned even if conversion/publish
                    // throws, or CameraX analysis stalls permanently.
                    try {
                        val now = System.currentTimeMillis()
                        if (liveViewActive) {
                            try {
                                val pts = System.nanoTime() / 1000
                                liveViewEncoder?.feedVideoFrame(image.image!!, pts)
                            } catch (e: Exception) {
                                Log.w(TAG, "LiveView feed failed", e)
                            }
                        }
                        if (active && now - lastPublishMs >= 250L) {
                            lastPublishMs = now
                            frameCount++
                            if (frameCount % 30 == 0L) {
                                Log.i(TAG, "frames=$frameCount (screen-on/off gate)")
                            }
                            CameraFrameBus.publish(toBgr(image), image.width, image.height)
                        }
                    } finally {
                        image.close()
                    }
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val rotation = displayRotation(service)
                val videoCapture = VideoClipRecorder.buildVideoCapture(rotation)
                val preview = if (allowPreview) {
                    Preview.Builder()
                        .setTargetRotation(rotation)
                        .build()
                        .also { p ->
                            boundPreview = p
                            pendingPreviewProvider?.let { p.setSurfaceProvider(it) }
                            Log.i(TAG, "preview use case built rotation=$rotation")
                        }
                } else null
                imageAnalysis = analysis
                imageCapture = capture
                provider.unbindAll()
                val lifecycleOwner: LifecycleOwner = service
                val groupBuilder = UseCaseGroup.Builder()
                    .addUseCase(analysis)
                    .addUseCase(capture)
                if (preview != null) {
                    groupBuilder.addUseCase(preview)
                }
                if (recordVideo) {
                    groupBuilder.addUseCase(videoCapture)
                }
                provider.bindToLifecycle(
                    lifecycleOwner, selector, groupBuilder.build()
                ).also { camera ->
                    onCameraBound(camera)
                    Log.i(TAG, "camera bound id=$cameraId")
                }
                if (recordVideo) {
                    VideoClipRecorder.onMonitoringStarted()
                }
                CameraEvents.publishPreviewStatus(preview != null)
            } catch (e: Exception) {
                if (allowPreview) {
                    // e.g. the device's camera can't serve Preview + Analysis +
                    // Capture + Video simultaneously; fall back to the existing
                    // analysis-only monitoring rather than failing the bind.
                    Log.w(TAG, "camera bind failed with preview; retrying without it", e)
                    boundPreview?.setSurfaceProvider(null)
                    boundPreview = null
                    bindCamera(service, cameraId, allowPreview = false)
                } else {
                    Log.e(TAG, "camera bind failed", e)
                    CameraEvents.publishPreviewStatus(false)
                }
            }
        }, ContextCompat.getMainExecutor(service))
    }

    /** Converts a CameraX YUV_420_888 frame to interleaved BGR. */
    private fun toBgr(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val width = image.width
        val height = image.height
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val out = ByteArray(width * height * 3)
        var outIdx = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = (row shr 1) * uvRowStride
            for (col in 0 until width) {
                val y = yBuf.get(yRow + col).toInt() and 0xFF
                val uvIdx = uvRow + (col shr 1) * uvPixelStride
                val u = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvIdx).toInt() and 0xFF) - 128
                val r = (y + (v * 1436 / 1024)).coerceIn(0, 255)
                val g = (y - (u * 352 / 1024) - (v * 731 / 1024)).coerceIn(0, 255)
                val b = (y + (u * 1814 / 1024)).coerceIn(0, 255)
                out[outIdx++] = b.toByte()
                out[outIdx++] = g.toByte()
                out[outIdx++] = r.toByte()
            }
        }
        return out
    }

    interface StillCallback {
        fun onResult(bytes: ByteArray)
        fun onError(message: String)
    }

    fun captureStill(callback: StillCallback) {
        val capture = imageCapture ?: run {
            callback.onError("camera not active")
            return
        }
        val service = activeService ?: run {
            callback.onError("camera service not running")
            return
        }
        Log.i(TAG, "captureStill enter")
        val file = java.io.File(service.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                val bytes = file.readBytes()
                file.delete()
                Log.i(TAG, "captureStill saved ${bytes.size} bytes")
                callback.onResult(bytes)
            }

            override fun onError(exc: ImageCaptureException) {
                Log.i(TAG, "captureStill error ${exc.message}")
                callback.onError(exc.message ?: "capture failed")
            }
        })
    }
}