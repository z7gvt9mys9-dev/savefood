package ru.savefood.app.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val EmberColors = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF1A0E08),
    primaryContainer = InkWarm,
    onPrimaryContainer = Cream,
    inversePrimary = EmberDeep,
    secondary = Honey,
    onSecondary = Color(0xFF241600),
    secondaryContainer = Color(0xFF3B2B16),
    onSecondaryContainer = Color(0xFFFFE4A8),
    tertiary = Sage,
    onTertiary = Color(0xFF12210E),
    tertiaryContainer = Color(0xFF253422),
    onTertiaryContainer = Color(0xFFD5F0C7),
    error = Danger,
    onError = Color(0xFF2E0503),
    errorContainer = Color(0xFF411412),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Ink,
    onBackground = Paper,
    surface = InkRaised,
    onSurface = Paper,
    surfaceVariant = InkSoft,
    onSurfaceVariant = MutedStrong,
    surfaceTint = Ember,
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    outline = Color(0xFF65584F),
    outlineVariant = Color(0xFF3B312B),
    scrim = Color(0xFF080706),
)
data class SaveFoodStatusColors(
    val pending: androidx.compose.ui.graphics.Color,
    val active: androidx.compose.ui.graphics.Color,
    val done: androidx.compose.ui.graphics.Color,
    val danger: androidx.compose.ui.graphics.Color,
    val neutral: androidx.compose.ui.graphics.Color,
)
val LocalStatusColors = staticCompositionLocalOf {
    SaveFoodStatusColors(
        pending = StatusColors.Pending,
        active = StatusColors.Active,
        done = StatusColors.Done,
        danger = StatusColors.Danger,
        neutral = StatusColors.Neutral,
    )
}
@Composable
fun SaveFoodTheme(
    content: @Composable () -> Unit,
) {
    val statusColors = SaveFoodStatusColors(
        pending = StatusColors.Pending,
        active = StatusColors.Active,
        done = StatusColors.Done,
        danger = StatusColors.Danger,
        neutral = StatusColors.Neutral,
    )
    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = EmberColors,
            typography = SaveFoodTypography,
            shapes = SaveFoodShapes,
            content = content,
        )
    }
}
