package ru.savefood.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Warm layered backdrop mirroring the subtle ember glow used on the website. */
@Composable
fun EmberBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val emberGlow = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(MaterialTheme.colorScheme.surfaceVariant, background),
                        startY = -250f,
                        endY = 1200f,
                    ),
                )
                .background(
                    Brush.radialGradient(
                        colors = listOf(emberGlow, Color.Transparent),
                        center = Offset(850f, -80f),
                        radius = 1000f,
                    ),
                ),
            content = content,
        )
    }
}
