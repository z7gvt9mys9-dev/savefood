package ru.savefood.app.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Neutral99,
    primaryContainer = Green90,
    onPrimaryContainer = Green10,
    secondary = Amber40,
    onSecondary = Neutral99,
    secondaryContainer = Amber90,
    onSecondaryContainer = Color(0xFF2A1700),
    error = Red40,
    onError = Neutral99,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Neutral95,
    onSurfaceVariant = Neutral20,
)

private val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Green20,
    primaryContainer = Green30,
    onPrimaryContainer = Green90,
    secondary = Amber80,
    onSecondary = Color(0xFF4A2800),
    secondaryContainer = Color(0xFF7A4A00),
    onSecondaryContainer = Amber90,
    error = Red80,
    onError = Color(0xFF690005),
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = Neutral20,
    onSurfaceVariant = Neutral90,
)

// Status colors aren't part of Material's ColorScheme, so expose them via a
// CompositionLocal that flips with the theme.
data class SaveFoodStatusColors(
    val pending: androidx.compose.ui.graphics.Color,
    val active: androidx.compose.ui.graphics.Color,
    val done: androidx.compose.ui.graphics.Color,
    val danger: androidx.compose.ui.graphics.Color,
    val neutral: androidx.compose.ui.graphics.Color,
)

val LocalStatusColors = staticCompositionLocalOf {
    SaveFoodStatusColors(
        pending = StatusColors.PendingLight,
        active = StatusColors.ActiveLight,
        done = StatusColors.DoneLight,
        danger = StatusColors.DangerLight,
        neutral = StatusColors.NeutralLight,
    )
}

@Composable
fun SaveFoodTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You dynamic color on Android 12+; brand palette as fallback.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val statusColors = if (darkTheme) {
        SaveFoodStatusColors(
            pending = StatusColors.PendingDark,
            active = StatusColors.ActiveDark,
            done = StatusColors.DoneDark,
            danger = StatusColors.DangerDark,
            neutral = StatusColors.NeutralDark,
        )
    } else {
        SaveFoodStatusColors(
            pending = StatusColors.PendingLight,
            active = StatusColors.ActiveLight,
            done = StatusColors.DoneLight,
            danger = StatusColors.DangerLight,
            neutral = StatusColors.NeutralLight,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SaveFoodTypography,
            shapes = SaveFoodShapes,
            content = content,
        )
    }
}
