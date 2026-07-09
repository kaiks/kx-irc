package com.kx.irc

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

internal val LightColors = lightColorScheme(
    primary = Color(0xFF1F2937),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFF59E0B),
    onSecondary = Color(0xFF1F2937),
    tertiary = Color(0xFF10B981),
    onTertiary = Color(0xFF052E24),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF334155),
    outline = Color(0xFF64748B)
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFE5E7EB),
    onPrimary = Color(0xFF111827),
    secondary = Color(0xFFF59E0B),
    onSecondary = Color(0xFF111827),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF052E24),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF94A3B8)
)

@Composable
fun KxIrcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
