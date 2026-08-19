package io.securitycam.security_cam.camera_service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import io.securitycam.security_cam.MainActivity
import java.util.concurrent.Executors

/**
 * Foreground service owning the CameraX session so the camera keeps streaming
 * with the screen off / Activity stopped. Analysis frames (preset resolution
 * BGR, ~4 fps) are published on [CameraFrameBus]; stills are captured on demand.
 */
class MonitoringService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        MonitoringServiceController.onStart(
            this,
            intent?.getStringExtra(EXTRA_CAMERA_ID) ?: "0",
            intent?.getStringExtra(EXTRA_CAMERA_NAME) ?: "Hallway",
            intent?.getIntExtra(EXTRA_PRE_ROLL, 5) ?: 5,
            intent?.getIntExtra(EXTRA_POST_ROLL, 5) ?: 5,
            intent?.getBooleanExtra(EXTRA_RECORD_VIDEO, true) ?: true,
            intent?.getStringExtra(EXTRA_VIDEO_QUALITY) ?: "lowest",
            intent?.getIntExtra(EXTRA_ANALYSIS_WIDTH, 320) ?: 320,
            intent?.getIntExtra(EXTRA_ANALYSIS_HEIGHT, 240) ?: 240,
        )
        return START_STICKY
    }

    override fun onDestroy() {
        MonitoringServiceController.onServiceDestroyed(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CAMERA_ID = "cameraId"
        const val EXTRA_CAMERA_NAME = "cameraName"
        const val EXTRA_PRE_ROLL = "preRollSeconds"
        const val EXTRA_POST_ROLL = "postRollSeconds"
        const val EXTRA_RECORD_VIDEO = "recordVideo"
        const val EXTRA_VIDEO_QUALITY = "videoQuality"
        const val EXTRA_ANALYSIS_WIDTH = "analysisWidth"
        const val EXTRA_ANALYSIS_HEIGHT = "analysisHeight"

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
            val intent = Intent(context, MonitoringService::class.java)
                .putExtra(EXTRA_CAMERA_ID, cameraId)
                .putExtra(EXTRA_CAMERA_NAME, cameraName)
                .putExtra(EXTRA_PRE_ROLL, preRollSeconds)
                .putExtra(EXTRA_POST_ROLL, postRollSeconds)
                .putExtra(EXTRA_RECORD_VIDEO, recordVideo)
                .putExtra(EXTRA_VIDEO_QUALITY, videoQuality)
                .putExtra(EXTRA_ANALYSIS_WIDTH, analysisWidth)
                .putExtra(EXTRA_ANALYSIS_HEIGHT, analysisHeight)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/**
 * Owns the FGS lifecycle + CameraX bind. Kept out of [MonitoringService] so the
 * same controller is reachable from the Dart channel handlers.
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
    private var frameCount = 0L
    private var lastPublishMs = 0L
    private var recordVideo = true
    private var analysisWidth = 320
    private var analysisHeight = 240

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
        if (active) return
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
        if (ContextCompat.checkSelfPermission(service, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CAMERA permission not granted")
            active = false
            return
        }
        if (ContextCompat.checkSelfPermission(service, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            // Native-owned mic: started before CameraX binds so the recorder can
            // reuse the live AudioRecord via AudioMixSource (API 31+).
            micCapture.start { pcm -> CameraServiceChannels.publishMicPcm(pcm) }
        } else {
            Log.w(TAG, "RECORD_AUDIO permission not granted; running video-only")
        }
        bindCamera(service, cameraId)
    }

    fun onServiceDestroyed(service: Service) {
        active = false
        cameraProvider?.unbindAll()
        imageAnalysis = null
        imageCapture = null
        micCapture.stop()
        VideoClipRecorder.onMonitoringStopped()
        releaseWakeLock()
    }

    fun stop() {
        active = false
        cameraProvider?.unbindAll()
        imageAnalysis = null
        imageCapture = null
        micCapture.stop()
        VideoClipRecorder.onMonitoringStopped()
        releaseWakeLock()
        val service = activeService
        if (service != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                service.stopForeground(true)
            }
            service.stopSelf()
        }
        activeService = null
    }

    private var activeService: LifecycleService? = null

    private fun startForeground(service: LifecycleService) {
        activeService = service
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // NotificationChannel only exists on API 26+; pre-O the channel API is
        // unavailable but notifications work without an explicit channel.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
            .setContentTitle("Monitoring active")
            .setContentText("Camera analysis is running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
        service.startForeground(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "security_cam:monitoring"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun bindCamera(service: LifecycleService, cameraId: String) {
        val providerFuture = ProcessCameraProvider.getInstance(service)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val selector = CameraSelector.DEFAULT_BACK_CAMERA.takeIf { cameraId != "front" }
                ?: CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(analysisWidth, analysisHeight))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor) { image: ImageProxy ->
                    val now = System.currentTimeMillis()
                    if (active && now - lastPublishMs >= 250L) {
                        lastPublishMs = now
                        frameCount++
                        if (frameCount % 30 == 0L) {
                            Log.i(TAG, "frames=$frameCount (screen-on/off gate)")
                        }
                        CameraFrameBus.publish(toBgr(image), image.width, image.height)
                    }
                    image.close()
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val videoCapture = VideoClipRecorder.buildVideoCapture()
                imageAnalysis = analysis
                imageCapture = capture
                provider.unbindAll()
                val lifecycleOwner: LifecycleOwner = service
                val groupBuilder = UseCaseGroup.Builder()
                    .addUseCase(analysis)
                    .addUseCase(capture)
                if (recordVideo) {
                    groupBuilder.addUseCase(videoCapture)
                }
                provider.bindToLifecycle(lifecycleOwner, selector, groupBuilder.build())
                if (recordVideo) {
                    VideoClipRecorder.onMonitoringStarted()
                }
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
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