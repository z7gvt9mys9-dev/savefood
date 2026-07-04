package ru.savefood.app.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import ru.savefood.app.core.designsystem.component.EmptyState

/**
 * Temporary screen for tabs whose full UI lands in the screens milestone.
 * Uses the design-system EmptyState so it already looks intentional.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    description: String = "Экран в разработке",
    icon: ImageVector = Icons.Filled.Construction,
) {
    EmptyState(icon = icon, title = title, description = description)
}
