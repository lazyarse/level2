package io.securitycam.level2.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF111111),
    background = Color(0xFF111111),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE0E0E0),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraSmall = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

/**
 * Square shape for every button. Material 3 1.3.x resolves button shapes
 * from a hardcoded CircleShape token (see ButtonDefaults.shape), so the
 * theme slots cannot change them — pass `shape = AppButtonShape`
 * explicitly on every Button/FilledTonalButton/OutlinedButton/TextButton.
 */
val AppButtonShape = RoundedCornerShape(2.dp)

@Composable
fun SecurityCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        shapes = AppShapes,
        content = content,
    )
}