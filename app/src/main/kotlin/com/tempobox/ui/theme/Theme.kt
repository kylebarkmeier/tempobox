package com.tempobox.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.tempobox.model.ThemeConfig

/**
 * App theme driven by the user's [ThemeConfig] (Settings ▸ Theme):
 * custom primary/secondary/tertiary colors, dark mode choice, and optional
 * Material You dynamic color (API 31+) which overrides the custom colors.
 */
@Composable
fun TempoBoxTheme(
    config: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit,
) {
    val dark = when (config.darkMode) {
        ThemeConfig.DarkMode.SYSTEM -> isSystemInDarkTheme()
        ThemeConfig.DarkMode.LIGHT -> false
        ThemeConfig.DarkMode.DARK -> true
    }

    val colorScheme: ColorScheme = when {
        config.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> customScheme(config, dark)
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * Builds a Material3 scheme around the three user-chosen seed colors.
 * Containers/on-colors are derived so any reasonable seed stays legible.
 */
private fun customScheme(config: ThemeConfig, dark: Boolean): ColorScheme {
    val primary = Color(config.primaryArgb.toInt())
    val secondary = Color(config.secondaryArgb.toInt())
    val tertiary = Color(config.tertiaryArgb.toInt())

    fun on(color: Color): Color = if (color.luminance() > 0.5f) Color.Black else Color.White
    fun container(color: Color): Color =
        if (dark) color.mix(Color.Black, 0.6f) else color.mix(Color.White, 0.75f)

    return if (dark) {
        darkColorScheme(
            primary = primary.mix(Color.White, 0.25f),
            onPrimary = on(primary.mix(Color.White, 0.25f)),
            primaryContainer = container(primary),
            onPrimaryContainer = on(container(primary)),
            secondary = secondary.mix(Color.White, 0.25f),
            onSecondary = on(secondary.mix(Color.White, 0.25f)),
            secondaryContainer = container(secondary),
            onSecondaryContainer = on(container(secondary)),
            tertiary = tertiary.mix(Color.White, 0.25f),
            onTertiary = on(tertiary.mix(Color.White, 0.25f)),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = on(primary),
            primaryContainer = container(primary),
            onPrimaryContainer = on(container(primary)),
            secondary = secondary,
            onSecondary = on(secondary),
            secondaryContainer = container(secondary),
            onSecondaryContainer = on(container(secondary)),
            tertiary = tertiary,
            onTertiary = on(tertiary),
        )
    }
}

/** Linear RGB mix of two colors ([amount] of [other]). */
private fun Color.mix(other: Color, amount: Float): Color =
    Color(
        red = red + (other.red - red) * amount,
        green = green + (other.green - green) * amount,
        blue = blue + (other.blue - blue) * amount,
        alpha = 1f,
    )
