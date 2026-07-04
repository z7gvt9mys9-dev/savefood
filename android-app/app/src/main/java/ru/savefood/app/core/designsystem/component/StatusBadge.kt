package ru.savefood.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.savefood.app.core.designsystem.theme.LocalStatusColors

/** Semantic state for a badge — maps to a theme-aware color. */
enum class BadgeTone { PENDING, ACTIVE, DONE, DANGER, NEUTRAL }

@Composable
fun StatusBadge(
    text: String,
    tone: BadgeTone,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStatusColors.current
    val color = when (tone) {
        BadgeTone.PENDING -> colors.pending
        BadgeTone.ACTIVE -> colors.active
        BadgeTone.DONE -> colors.done
        BadgeTone.DANGER -> colors.danger
        BadgeTone.NEUTRAL -> colors.neutral
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
