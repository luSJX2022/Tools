package com.qzkt.timetable.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build

/** 主题模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF4C6178),
    surface = Color(0xFFF7F9FC),
    surfaceVariant = Color(0xFFE3E8F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF00306B),
    primaryContainer = Color(0xFF0D47A1),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFB3C8E0),
    surface = Color(0xFF11131A),
    surfaceVariant = Color(0xFF2A2F38),
)

@Composable
fun QzktTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = rememberIsDarkTheme(themeMode)

    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        dark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = baseScheme.withGlassSurfaces(dark), content = content)
}

private fun ColorScheme.withGlassSurfaces(dark: Boolean): ColorScheme {
    val tint = if (dark) Color(0xFF142235) else Color(0xFFEAF2FF)
    val glass = tint.copy(alpha = if (dark) 0.76f else 0.72f)
    val glassSoft = tint.copy(alpha = if (dark) 0.62f else 0.64f)
    val glassStrong = tint.copy(alpha = if (dark) 0.86f else 0.84f)
    return copy(
        background = Color.Transparent,
        surface = glass,
        surfaceVariant = glassSoft,
        surfaceDim = tint.copy(alpha = if (dark) 0.80f else 0.68f),
        surfaceBright = glassStrong,
        surfaceContainerLowest = tint.copy(alpha = if (dark) 0.48f else 0.48f),
        surfaceContainerLow = glassSoft,
        surfaceContainer = glass,
        surfaceContainerHigh = glassStrong,
        surfaceContainerHighest = tint.copy(alpha = if (dark) 0.92f else 0.92f),
    )
}

/**
 * 主题模式 → 当前是不是深色。
 *
 * QzktTheme 用它挑配色；内嵌的网页（词达人解析）也用它决定网页的浅色 / 深色，
 * 这样「主题由 Tools 统一设置」只有一处判断。
 */
@Composable
fun rememberIsDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
