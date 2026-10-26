package io.github.ahdcamera

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Persisted independently of capture configuration; changing appearance never restarts streams. */
@Composable
internal fun WallTheme(content: @Composable (String, (String) -> Unit) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("wall-appearance", 0) }
    var appearance by remember { mutableStateOf(preferences.getString("theme", "dark").orEmpty()) }
    val dark = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(
        primary = WallPalette.Accent, onPrimary = Color(0xFF062C28),
        background = Color(0xFF05080B), onBackground = Color(0xFFE7F1F4),
        surface = Color(0xFF0F161E), onSurface = Color(0xFFE7F1F4),
        surfaceVariant = Color(0xFF111C25), onSurfaceVariant = Color(0xFFA3AFB9),
        outline = Color(0xFF405560),
    ) else lightColorScheme(
        primary = Color(0xFF007D70), onPrimary = Color.White,
        background = Color(0xFF05080B), onBackground = Color(0xFFE7F1F4),
        surface = Color(0xFFF4F9FA), onSurface = Color(0xFF17232B),
        surfaceVariant = Color.White, onSurfaceVariant = Color(0xFF51616D),
        outline = Color(0xFF98A9B4),
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
            content(appearance) { value ->
                appearance = value
                preferences.edit().putString("theme", value).apply()
            }
        }
    }
}
