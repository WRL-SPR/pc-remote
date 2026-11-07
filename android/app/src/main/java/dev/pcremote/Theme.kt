package dev.pcremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

private val LightColors = lightColorScheme(
    primary = Color(0xFF3651D4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF0B1A63),
    secondary = Color(0xFF586079),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E9F4),
    onSecondaryContainer = Color(0xFF1A2033),
    tertiary = Color(0xFF00897B),
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF181B23),
    surface = Color(0xFFF5F6FA),
    onSurface = Color(0xFF181B23),
    onSurfaceVariant = Color(0xFF5E6474),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEEF0F6),
    surfaceContainerHigh = Color(0xFFE8EBF2),
    surfaceContainerHighest = Color(0xFFE2E5EE),
    outline = Color(0xFF8A8FA0),
    outlineVariant = Color(0xFFD5D8E2),
    error = Color(0xFFD32F2F),
    errorContainer = Color(0xFFFFE3E0),
    onErrorContainer = Color(0xFF5F0B07),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA3B4FF),
    onPrimary = Color(0xFF0B1A63),
    primaryContainer = Color(0xFF2B40A8),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFBFC5DA),
    onSecondary = Color(0xFF292F42),
    secondaryContainer = Color(0xFF282D3A),
    onSecondaryContainer = Color(0xFFDCE1EE),
    tertiary = Color(0xFF5FD4C4),
    background = Color(0xFF0E1015),
    onBackground = Color(0xFFE4E6EE),
    surface = Color(0xFF0E1015),
    onSurface = Color(0xFFE4E6EE),
    onSurfaceVariant = Color(0xFFA6ABBA),
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF171A21),
    surfaceContainer = Color(0xFF1C1F27),
    surfaceContainerHigh = Color(0xFF232733),
    surfaceContainerHighest = Color(0xFF2B303D),
    outline = Color(0xFF6E7485),
    outlineVariant = Color(0xFF30353F),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF4A1714),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun PcRemoteTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

/** Status colours that stay readable on both the light and dark card backgrounds. */
object StatusColors {
    @Composable
    private fun isDark() = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val ready: Color @Composable get() = if (isDark()) Color(0xFF4ADE80) else Color(0xFF16A34A)
    val waiting: Color @Composable get() = if (isDark()) Color(0xFFFBBF24) else Color(0xFFD97706)
    val off: Color @Composable get() = MaterialTheme.colorScheme.outline
}
