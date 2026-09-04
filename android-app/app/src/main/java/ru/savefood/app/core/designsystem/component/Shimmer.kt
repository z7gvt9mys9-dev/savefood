package ru.savefood.app.core.designsystem.component
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
/** Pulsing placeholder block for skeleton loading states. */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer-alpha",
    )
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
/** A few stacked shimmer lines approximating a list card while loading. */
@Composable
fun ShimmerListItem(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f), height = 20.dp)
        ShimmerBox(modifier = Modifier.fillMaxWidth(), height = 14.dp)
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.8f), height = 14.dp)
    }
}
