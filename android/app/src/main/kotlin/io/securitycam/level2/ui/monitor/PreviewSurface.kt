package io.securitycam.level2.ui.monitor

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.securitycam.level2.camera_service.MonitoringServiceController

/**
 * Live camera passthrough backed by a native CameraX [PreviewView]
 * (`COMPATIBLE` implementation). The surface provider is handed to the
 * monitoring service so the FGS can stream into it; on dispose (tab switch,
 * screen off) the provider is detached while analysis/video keep running.
 *
 * CameraX applies the SurfaceTexture transform for orientation, so the preview
 * renders upright on its own. A [DisplayManager.DisplayListener] re-applies the
 * target rotation when the display changes.
 *
 * [fillCrop]: FILL_CENTER crops overflow when aspect ratios differ (monitor
 * screen). Pass false for FIT_CENTER letterboxing — the zone editor needs
 * uncropped geometry so drawn zones map 1:1 onto analyzed frames.
 */
@Composable
fun PreviewSurface(modifier: Modifier = Modifier, fillCrop: Boolean = true) {
    val context = LocalContext.current
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    DisposableEffect(Unit) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val rotation =
                    displayManager.getDisplay(displayId)?.rotation ?: Surface.ROTATION_0
                MonitoringServiceController.setTargetRotation(rotation)
            }
        }
        // registerDisplayListener is API 31+; `registerListener` was removed from
        // the SDK 37 stubs. On <31 the initial-rotation path still applies.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                displayManager.unregisterDisplayListener(listener)
            }
            MonitoringServiceController.setPreviewSurfaceProvider(null)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = if (fillCrop) {
                    PreviewView.ScaleType.FILL_CENTER
                } else {
                    PreviewView.ScaleType.FIT_CENTER
                }
                MonitoringServiceController.setPreviewSurfaceProvider(surfaceProvider)
            }
        },
    )
}