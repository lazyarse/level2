package io.securitycam.security_cam.camera_service

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec

/**
 * Dart-facing channels for the camera foreground service.
 *
 * MethodChannel `io.securitycam.security_cam/camera`:
 *   startMonitoring(cameraId) / stopMonitoring / captureStill -> JPEG bytes
 * EventChannel `io.securitycam.security_cam/frames`:
 *   {width, height, gray} grayscale analysis frames @ ~4 fps
 */
class CameraServiceChannels private constructor() {
    companion object {
        private const val CHANNEL = "io.securitycam.security_cam/camera"
        private const val FRAMES = "io.securitycam.security_cam/frames"

        private var context: Context? = null
        private var methodChannel: MethodChannel? = null
        private var eventChannel: EventChannel? = null
        private var frameSink: EventChannel.EventSink? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun attach(messenger: BinaryMessenger, appContext: Context) {
            detach(messenger)
            context = appContext
            methodChannel = MethodChannel(messenger, CHANNEL, StandardMethodCodec.INSTANCE)
                .apply { setMethodCallHandler(::handle) }
            eventChannel = EventChannel(messenger, FRAMES, StandardMethodCodec.INSTANCE)
                .apply {
                    setStreamHandler(object : EventChannel.StreamHandler {
                        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                            frameSink = events
                            CameraFrameBus.add(::publishFrame)
                        }

                        override fun onCancel(arguments: Any?) {
                            CameraFrameBus.remove(::publishFrame)
                            frameSink = null
                        }
                    })
                }
        }

        fun detach(messenger: BinaryMessenger) {
            methodChannel?.setMethodCallHandler(null)
            methodChannel = null
            eventChannel?.setStreamHandler(null)
            eventChannel = null
            frameSink = null
            context = null
        }

        private fun publishFrame(gray: ByteArray, width: Int, height: Int) {
            mainHandler.post {
                frameSink?.success(mapOf("width" to width, "height" to height, "gray" to gray))
            }
        }

        private fun handle(call: MethodCall, result: MethodChannel.Result) {
            when (call.method) {
                "startMonitoring" -> {
                    val appContext = context
                    if (appContext == null) {
                        result.error("camera_start_failed", "context unavailable", null)
                        return
                    }
                    try {
                        MonitoringService.start(
                            appContext,
                            call.argument<String>("cameraId") ?: "0",
                            call.argument<String>("cameraName") ?: "Hallway",
                            call.argument<Number>("preRollSeconds")?.toInt() ?: 5,
                            call.argument<Number>("postRollSeconds")?.toInt() ?: 5,
                            call.argument<Boolean>("recordVideo") ?: true,
                            call.argument<String>("videoQuality") ?: "lowest",
                        )
                        result.success(null)
                    } catch (e: Exception) {
                        result.error("camera_start_failed", e.message, null)
                    }
                }
                "stopMonitoring" -> {
                    MonitoringServiceController.stop()
                    result.success(null)
                }
                "captureStill" -> {
                    MonitoringServiceController.captureStill(object :
                        MonitoringServiceController.StillCallback {
                        override fun onResult(bytes: ByteArray) = result.success(bytes)
                        override fun onError(message: String) =
                            result.error("capture_failed", message, null)
                    })
                }
                "exportVideoClip" -> {
                    val triggerAtMs = call.argument<Number>("triggerTimestampMs")?.toLong()
                    if (triggerAtMs == null) {
                        result.error("video_export_failed", "triggerTimestampMs required", null)
                        return
                    }
                    VideoClipRecorder.exportClip(
                        triggerAtMs,
                        call.argument<Number>("preRollSeconds")?.toInt() ?: 5,
                        call.argument<Number>("postRollSeconds")?.toInt() ?: 5,
                        call.argument<String>("cameraName") ?: "Hallway",
                    ) { name -> result.success(name) }
                }
                "deleteVideo" -> {
                    VideoClipRecorder.delete(call.argument<String>("name") ?: "")
                    result.success(null)
                }
                "openVideo" -> {
                    VideoClipRecorder.open(call.argument<String>("name") ?: "")
                    result.success(null)
                }
                "videoExists" -> {
                    result.success(VideoClipRecorder.exists(call.argument<String>("name") ?: ""))
                }
                "videoInfo" -> {
                    result.success(
                        VideoClipRecorder.videoInfo(call.argument<String>("name") ?: "")
                    )
                }
                else -> result.notImplemented()
            }
        }
    }
}