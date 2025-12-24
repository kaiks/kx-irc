package com.kx.irc

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F2937),
    secondary = Color(0xFFF59E0B),
    tertiary = Color(0xFF10B981),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE5E7EB),
    secondary = Color(0xFFF59E0B),
    tertiary = Color(0xFF34D399),
    background = Color(0xFF0F172A),
    surface = Color(0xFF111827)
)

@Composable
fun KxIrcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
