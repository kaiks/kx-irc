package com.kx.irc

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorsTest {
    @Test
    fun darkThemeUsesLightContentColorsOnDarkSurfaces() {
        assertEquals(Color(0xFFE5E7EB), DarkColors.onBackground)
        assertEquals(Color(0xFFE5E7EB), DarkColors.onSurface)
        assertEquals(Color(0xFFCBD5E1), DarkColors.onSurfaceVariant)
    }

    @Test
    fun lightThemeUsesDarkContentColorsOnLightSurfaces() {
        assertEquals(Color(0xFF111827), LightColors.onBackground)
        assertEquals(Color(0xFF111827), LightColors.onSurface)
    }
}
