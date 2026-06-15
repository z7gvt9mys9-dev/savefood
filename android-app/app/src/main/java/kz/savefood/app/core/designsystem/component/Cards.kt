package kz.savefood.app.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Standard surface card used across lists and detail screens. */
@Composable
fun SaveFoodCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val cardModifier = modifier.fillMaxWidth()
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) { CardBody(content) }
    } else {
        Card(
            modifier = cardModifier,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) { CardBody(content) }
    }
}

@Composable
private fun CardBody(content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) { content() }
}

/** Section title with optional trailing action label (e.g. "See all"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (actionLabel != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
