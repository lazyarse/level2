package io.securitycam.level2

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.securitycam.level2.camera_service.availableCameras
import io.securitycam.level2.ui.theme.SecurityCamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (availableCameras(applicationContext).isEmpty()) {
            Toast.makeText(this, "No cameras found on device", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        io.securitycam.level2.detection.person.AppContextHolder.context = applicationContext
        enableEdgeToEdge()
        setContent {
            SecurityCamTheme {
                SecurityCamApp()
            }
        }
    }
}