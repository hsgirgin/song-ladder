package com.songladder.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B35),
    onPrimary = Color(0xFF101318),
    secondary = Color(0xFF8FA8FF),
    background = Color(0xFF091019),
    surface = Color(0xFF111923),
    surfaceVariant = Color(0xFF172331),
    onSurface = Color(0xFFF5F7FA),
    onSurfaceVariant = Color(0xFF91A4B7)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE4571E),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF375EF5),
    background = Color(0xFFF5F1EB),
    surface = Color(0xFFFFFBF7),
    surfaceVariant = Color(0xFFF1E8DF),
    onSurface = Color(0xFF131A21),
    onSurfaceVariant = Color(0xFF586575)
)

@Composable
fun SongLadderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = SongLadderTypography,
        content = content
    )
}
