package io.securitycam.security_cam

import android.content.Context
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.securitycam.security_cam.camera_service.CameraServiceChannels

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val context: Context = applicationContext
        CameraServiceChannels.attach(
            flutterEngine.dartExecutor.binaryMessenger,
            flutterEngine.renderer,
            this,
            context,
        )
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        CameraServiceChannels.detach(flutterEngine.dartExecutor.binaryMessenger)
        super.cleanUpFlutterEngine(flutterEngine)
    }
}