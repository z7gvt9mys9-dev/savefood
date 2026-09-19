package ru.savefood.app.core.designsystem.component

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared, non-blocking alert shown over the top of a role screen. */
@Composable
fun TopNoticeBanner(
    title: String,
    message: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var noticeHeight by remember { mutableIntStateOf(0) }
    val dragState = rememberDraggableState { delta ->
        dragOffsetY = (dragOffsetY + delta).coerceAtMost(0f)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { noticeHeight = it.height }
            .graphicsLayer { translationY = dragOffsetY }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val shouldDismiss = noticeHeight > 0 &&
                        (dragOffsetY <= -noticeHeight * 0.25f || velocity <= -900f)
                    if (shouldDismiss) {
                        // Let the host's exit transition continue from the dragged position
                        // immediately instead of waiting for a second animation to finish.
                        onDismiss()
                    } else {
                        animate(
                            initialValue = dragOffsetY,
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 110),
                        ) { value, _ ->
                            dragOffsetY = value
                        }
                    }
                },
            )
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 12.dp,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 6.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = dismissLabel,
                )
            }
        }
    }
}
