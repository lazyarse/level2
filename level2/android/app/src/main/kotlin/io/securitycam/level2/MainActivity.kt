package io.securitycam.level2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.securitycam.level2.ui.theme.SecurityCamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        io.securitycam.level2.detection.person.AppContextHolder.context = applicationContext
        enableEdgeToEdge()
        setContent {
            SecurityCamTheme {
                SecurityCamApp()
            }
        }
    }
}