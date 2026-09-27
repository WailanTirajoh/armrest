package io.github.wailantirajoh.armrest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palet dari mockup UI (token --pr, --bg, --sf, dst.).
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FBF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6F8),
    onPrimaryContainer = Color(0xFF133B7A),
    background = Color(0xFFF6F6F2),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFECEDE7),
    onSurfaceVariant = Color(0xFF51524C),
    outline = Color(0xFFD3D4CC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C4F5),
    onPrimary = Color(0xFF0D2C5E),
    primaryContainer = Color(0xFF1F3A66),
    onPrimaryContainer = Color(0xFFDCE6F8),
    background = Color(0xFF121311),
    onBackground = Color(0xFFE7E7E1),
    surface = Color(0xFF1D1E1B),
    onSurface = Color(0xFFE7E7E1),
    surfaceVariant = Color(0xFF2A2B27),
    onSurfaceVariant = Color(0xFFB7B8B0),
    outline = Color(0xFF3C3D38),
)

@Composable
fun ArmrestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
