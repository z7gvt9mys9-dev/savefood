package ru.savefood.app.feature.needy.history
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.EmptyState
import ru.savefood.app.core.designsystem.component.SaveFoodCard
import ru.savefood.app.core.designsystem.component.SectionHeader
import ru.savefood.app.core.designsystem.component.ShimmerListItem
import ru.savefood.app.core.designsystem.component.StatusBadge
import ru.savefood.app.core.designsystem.component.BadgeTone
import ru.savefood.app.feature.needy.data.TicketDto
import ru.savefood.app.feature.needy.ui.ConfirmDialog
import ru.savefood.app.feature.needy.ui.RatingDialog
/** "History" tab: completed pickup details and active-request cancellation. */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    var rateTicket by remember { mutableStateOf<TicketDto?>(null) }
    var ticketToCancel by remember { mutableStateOf<TicketDto?>(null) }
    var photoForTicket by remember { mutableStateOf<Int?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val ticketId = photoForTicket
        if (uri != null && ticketId != null) viewModel.uploadPhoto(ticketId, uri)
        photoForTicket = null
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SectionHeader(title = stringResource(R.string.needy_history_title))
            when {
                state.loading -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(3) { ShimmerListItem() }
                }
                state.error != null -> EmptyState(
                    icon = Icons.Filled.History,
                    title = stringResource(R.string.common_error_generic),
                    description = state.error,
                    actionLabel = stringResource(R.string.needy_retry),
                    onAction = viewModel::load,
                )
                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.History,
                    title = stringResource(R.string.needy_history_empty_title),
                    description = stringResource(R.string.needy_history_empty_desc),
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    items(state.items, key = { it.id }) { ticket ->
                        HistoryCard(
                            ticket = ticket,
                            busy = state.busyTicketId == ticket.id,
                            onRate = { rateTicket = ticket },
                            onAddPhoto = {
                                photoForTicket = ticket.id
                                photoPicker.launch("image/*")
                            },
                            onCancel = { ticketToCancel = ticket },
                        )
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
    rateTicket?.let { ticket ->
        RatingDialog(
            initialRating = ticket.rating ?: 0,
            initialComment = ticket.ratingComment.orEmpty(),
            submitting = state.busyTicketId == ticket.id,
            onDismiss = { rateTicket = null },
            onSubmit = { rating, comment ->
                viewModel.rate(ticket.id, rating, comment)
                rateTicket = null
            },
        )
    }
    ticketToCancel?.let { ticket ->
        ConfirmDialog(
            title = stringResource(R.string.needy_history_cancel_confirm_title),
            text = stringResource(R.string.needy_history_cancel_confirm_desc),
            confirmLabel = stringResource(R.string.needy_history_cancel),
            onConfirm = {
                HistoryTicketActions.runCancellation(ticket) { viewModel.cancel(ticket) }
                ticketToCancel = null
            },
            onDismiss = { ticketToCancel = null },
        )
    }
}
@Composable
private fun HistoryCard(
    ticket: TicketDto,
    busy: Boolean,
    onRate: () -> Unit,
    onAddPhoto: () -> Unit,
    onCancel: () -> Unit,
) {
    val isFulfilled = ticket.status == "fulfilled"
    val canRate = isFulfilled && ticket.assignedVolunteerId != null
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ticket.items?.takeIf { it.isNotBlank() } ?: "#${ticket.id}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusBadge(
                    text = statusLabel(ticket.status),
                    tone = if (isFulfilled) BadgeTone.DONE else BadgeTone.NEUTRAL,
                )
            }
            (ticket.fulfilledAt ?: ticket.createdAt).let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ticket.rating?.let { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.needy_history_rated, r),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            ticket.deliveryPhotoStatus?.let { ps ->
                val label = when (ps) {
                    "approved" -> stringResource(R.string.needy_history_photo_approved)
                    "rejected" -> stringResource(R.string.needy_history_photo_rejected)
                    else -> stringResource(R.string.needy_history_photo_pending)
                }
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isFulfilled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canRate) {
                        TextButton(onClick = onRate, enabled = !busy) {
                            Text(
                                if (ticket.rating != null) stringResource(R.string.needy_history_rerate)
                                else stringResource(R.string.needy_history_rate),
                            )
                        }
                    }
                    TextButton(onClick = onAddPhoto, enabled = !busy) {
                        Icon(Icons.Filled.AddAPhoto, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text(stringResource(R.string.needy_history_add_photo))
                    }
                }
            }
            if (HistoryTicketActions.actionFor(ticket) == HistoryTicketAction.CANCEL_ACTIVE_REQUEST) {
                TextButton(onClick = onCancel, enabled = !busy) {
                    Text(
                        stringResource(R.string.needy_history_cancel),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
@Composable
private fun statusLabel(status: String): String = when (status) {
    "fulfilled" -> stringResource(R.string.needy_track_stage_delivered)
    "cancelled" -> stringResource(R.string.needy_track_cancelled)
    "assigned" -> stringResource(R.string.needy_track_stage_accepted)
    else -> stringResource(R.string.needy_track_stage_waiting)
}
