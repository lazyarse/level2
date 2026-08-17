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
 * with the screen off / Activity stopped. Analysis frames (160x120 grayscale,
 * ~4 fps) are published on [CameraFrameBus]; stills are captured on demand.
 */
class MonitoringService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        MonitoringServiceController.onStart(this, intent?.getStringExtra(EXTRA_CAMERA_ID) ?: "0")
        return START_STICKY
    }

    override fun onDestroy() {
        MonitoringServiceController.onServiceDestroyed(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CAMERA_ID = "cameraId"

        fun start(context: Context, cameraId: String) {
            val intent = Intent(context, MonitoringService::class.java)
                .putExtra(EXTRA_CAMERA_ID, cameraId)
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
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var active = false
    private var frameCount = 0L
    private var lastPublishMs = 0L

    fun onStart(service: LifecycleService, cameraId: String) {
        if (active) return
        active = true
        frameCount = 0
        startForeground(service)
        acquireWakeLock(service)
        if (ContextCompat.checkSelfPermission(service, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CAMERA permission not granted")
            active = false
            return
        }
        bindCamera(service, cameraId)
    }

    fun onServiceDestroyed(service: Service) {
        active = false
        cameraProvider?.unbindAll()
        imageAnalysis = null
        imageCapture = null
        releaseWakeLock()
    }

    fun stop() {
        active = false
        cameraProvider?.unbindAll()
        imageAnalysis = null
        imageCapture = null
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
        val channel = NotificationChannel(
            CHANNEL_ID, "Monitoring", NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
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
                    .setTargetResolution(android.util.Size(160, 120))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image: ImageProxy ->
                    val now = System.currentTimeMillis()
                    if (active && now - lastPublishMs >= 250L) {
                        lastPublishMs = now
                        frameCount++
                        if (frameCount % 30 == 0L) {
                            Log.i(TAG, "frames=$frameCount (screen-on/off gate)")
                        }
                        CameraFrameBus.publish(toGray(image), image.width, image.height)
                    }
                    image.close()
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageAnalysis = analysis
                imageCapture = capture
                provider.unbindAll()
                val lifecycleOwner: LifecycleOwner = service
                val group = UseCaseGroup.Builder()
                    .addUseCase(analysis)
                    .addUseCase(capture)
                    .build()
                provider.bindToLifecycle(lifecycleOwner, selector, group)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(service))
    }

    /** Extracts the grayscale Y plane, handling row/pixel strides. */
    private fun toGray(image: ImageProxy): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height)
        val row = ByteArray(width)
        for (rowIndex in 0 until height) {
            buffer.position(rowIndex * rowStride)
            buffer.get(row, 0, width)
            System.arraycopy(row, 0, out, rowIndex * width, width)
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
        val file = java.io.File(service.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                val bytes = file.readBytes()
                file.delete()
                callback.onResult(bytes)
            }

            override fun onError(exc: ImageCaptureException) {
                callback.onError(exc.message ?: "capture failed")
            }
        })
    }
}