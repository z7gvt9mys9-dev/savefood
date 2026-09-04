package ru.savefood.app.feature.needy.tracking
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yandex.mapkit.geometry.Point
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.EmptyState
import ru.savefood.app.core.designsystem.component.SaveFoodCard
import ru.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import ru.savefood.app.core.designsystem.component.SectionHeader
import ru.savefood.app.core.designsystem.component.ShimmerListItem
import ru.savefood.app.core.designsystem.component.StatusBadge
import ru.savefood.app.core.designsystem.component.BadgeTone
import ru.savefood.app.core.device.map.MapMarker
import ru.savefood.app.core.device.map.YandexMap
import ru.savefood.app.core.device.qr.QrImage
import ru.savefood.app.feature.needy.data.TicketDto
import ru.savefood.app.feature.needy.ui.ConfirmDialog
import ru.savefood.app.feature.needy.ui.RatingDialog
import ru.savefood.app.feature.needy.ui.TrackStage
@Composable
fun TrackingScreen(
    onGoFindFood: () -> Unit,
    viewModel: TrackingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    var cancelDialogTicket by remember { mutableStateOf<Int?>(null) }
    var rateDialogTicket by remember { mutableStateOf<TicketDto?>(null) }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SectionHeader(title = stringResource(R.string.needy_tickets_title))
        when {
            state.loading && state.tickets.isEmpty() -> {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(2) { ShimmerListItem() }
                }
            }
            state.error != null && state.tickets.isEmpty() -> {
                EmptyState(
                    icon = Icons.Filled.LocalShipping,
                    title = stringResource(R.string.common_error_generic),
                    description = state.error,
                    actionLabel = stringResource(R.string.needy_retry),
                    onAction = viewModel::retry,
                )
            }
            state.tickets.isEmpty() -> {
                EmptyState(
                    icon = Icons.Filled.LocalShipping,
                    title = stringResource(R.string.needy_tickets_empty_title),
                    description = stringResource(R.string.needy_tickets_empty_desc),
                )
            }
            else -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.stale) {
                        Text(
                            text = stringResource(R.string.needy_track_stale),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    state.tickets.forEach { ticket ->
                        TrackingCard(
                            ticket = ticket,
                            volLat = state.volunteerLocation?.lat,
                            volLon = state.volunteerLocation?.lon,
                            cancelling = state.cancellingTicketId == ticket.id,
                            onCancel = { cancelDialogTicket = ticket.id },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
    cancelDialogTicket?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.needy_track_cancel_confirm_title),
            text = stringResource(R.string.needy_track_cancel_confirm_desc),
            confirmLabel = stringResource(R.string.needy_track_cancel),
            onConfirm = {
                viewModel.cancelTicket(id)
                cancelDialogTicket = null
            },
            onDismiss = { cancelDialogTicket = null },
        )
    }
    rateDialogTicket?.let { ticket ->
        RatingDialog(
            initialRating = ticket.rating ?: 0,
            initialComment = ticket.ratingComment.orEmpty(),
            onDismiss = { rateDialogTicket = null },
            onSubmit = { rating, comment ->
                viewModel.rateTicket(ticket.id, rating, comment)
                rateDialogTicket = null
            },
        )
    }
}
@Composable
private fun TrackingCard(
    ticket: TicketDto,
    volLat: Double?,
    volLon: Double?,
    cancelling: Boolean,
    onCancel: () -> Unit,
) {
    val hasLiveLoc = volLat != null && volLon != null
    val stage = TrackStage.from(ticket.status, hasLiveLoc)
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                if (ticket.selfPickup == true) {
                    StatusBadge(text = stringResource(R.string.needy_track_self_pickup), tone = BadgeTone.NEUTRAL)
                }
            }
            if (stage == TrackStage.CANCELLED) {
                StatusBadge(text = stringResource(R.string.needy_track_cancelled), tone = BadgeTone.DANGER)
            } else {
                StatusLadder(stage)
            }
            if (ticket.assignedVolunteerId != null && ticket.selfPickup != true) {
                CourierRow(ticket.assignedVolunteerId)
                if (hasLiveLoc) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    ) {
                        val center = Point(volLat!!, volLon!!)
                        YandexMap(
                            markers = listOf(
                                MapMarker("courier", volLat, volLon, stringResource(R.string.needy_track_courier)),
                            ) + buildList {
                                if (ticket.lat != null && ticket.lon != null) {
                                    add(MapMarker("home", ticket.lat, ticket.lon))
                                }
                            },
                            center = center,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        text = stringResource(R.string.needy_track_eta),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.needy_track_no_location),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ticket.qrCode?.takeIf { it.isNotBlank() }?.let { qr ->
                Text(
                    text = stringResource(R.string.needy_track_your_qr),
                    style = MaterialTheme.typography.titleSmall,
                )
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    QrImage(content = qr, modifier = Modifier.size(160.dp))
                }
            }
            if (ticket.status == "open" || ticket.status == "assigned") {
                SaveFoodOutlinedButton(
                    text = stringResource(R.string.needy_track_cancel),
                    onClick = onCancel,
                    enabled = !cancelling,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
@Composable
private fun StatusLadder(stage: TrackStage) {
    val labels = listOf(
        stringResource(R.string.needy_track_stage_waiting),
        stringResource(R.string.needy_track_stage_accepted),
        stringResource(R.string.needy_track_stage_on_the_way),
        stringResource(R.string.needy_track_stage_delivered),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val reached = stage.step >= index
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (reached) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (reached) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (stage.step == index) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (reached) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
@Composable
private fun CourierRow(volunteerId: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.needy_track_courier),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.needy_track_courier_assigned, volunteerId),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
