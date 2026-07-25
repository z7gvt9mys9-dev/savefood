package ru.savefood.app.feature.volunteer.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.BadgeTone
import ru.savefood.app.core.designsystem.component.EmptyState
import ru.savefood.app.core.designsystem.component.SaveFoodButton
import ru.savefood.app.core.designsystem.component.SaveFoodCard
import ru.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import ru.savefood.app.core.designsystem.component.SectionHeader
import ru.savefood.app.core.designsystem.component.StatusBadge
import ru.savefood.app.core.device.Navigation
import ru.savefood.app.core.device.camera.CameraPreview
import ru.savefood.app.core.device.camera.captureToFile
import ru.savefood.app.core.device.camera.rememberCameraPermissionState
import ru.savefood.app.core.device.camera.rememberImageCapture
import ru.savefood.app.core.device.qr.QrScannerScreen
import ru.savefood.app.feature.volunteer.data.RoutePointDto
import ru.savefood.app.feature.volunteer.ui.VolunteerConfirmDialog

private const val ROUTE_MAIN = "route/main"
private const val ROUTE_SCAN = "route/scan"
private const val ROUTE_PHOTO = "route/photo"

/** "My route" tab: pickup → deliveries with QR + photo confirmation (nested nav). */
@Composable
fun RouteScreen(viewModel: RouteViewModel = hiltViewModel()) {
    val nav = rememberNavController()

    // Reload whenever the tab is shown; tracking starts inside the VM on success.
    LaunchedEffect(Unit) { viewModel.load() }

    // Holds the QR scanned for the ticket awaiting photo confirmation.
    var pendingTicketId by remember { mutableStateOf<Int?>(null) }
    var pendingQr by remember { mutableStateOf<String?>(null) }

    NavHost(navController = nav, startDestination = ROUTE_MAIN) {
        composable(ROUTE_MAIN) {
            RouteMain(
                viewModel = viewModel,
                onScan = { ticketId -> nav.navigate("$ROUTE_SCAN/$ticketId") },
            )
        }
        composable(
            route = "$ROUTE_SCAN/{ticketId}",
            arguments = listOf(navArgument("ticketId") { type = NavType.IntType }),
        ) { entry ->
            val ticketId = entry.arguments?.getInt("ticketId") ?: return@composable
            QrScannerScreen(
                onResult = { code ->
                    pendingTicketId = ticketId
                    pendingQr = code
                    nav.navigate(ROUTE_PHOTO) {
                        popUpTo(ROUTE_MAIN)
                    }
                },
                onCancel = { nav.popBackStack() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(ROUTE_PHOTO) {
            val ticketId = pendingTicketId
            val qr = pendingQr
            if (ticketId == null || qr == null) {
                LaunchedEffect(Unit) { nav.popBackStack(ROUTE_MAIN, inclusive = false) }
            } else {
                DeliveryPhotoScreen(
                    onConfirm = {
                        viewModel.confirmDelivery(ticketId, qr) {
                            pendingTicketId = null
                            pendingQr = null
                            nav.popBackStack(ROUTE_MAIN, inclusive = false)
                        }
                    },
                    onCancel = { nav.popBackStack(ROUTE_MAIN, inclusive = false) },
                    confirming = viewModel.state.collectAsStateWithLifecycle().value.busyPointTicketId == ticketId,
                )
            }
        }
    }
}

@Composable
private fun RouteMain(
    viewModel: RouteViewModel,
    onScan: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var finishDialog by remember { mutableStateOf(false) }

    val noLocationMsg = stringResource(R.string.vol_route_no_location)
    LaunchedEffect(state.actionError) {
        state.actionError?.let {
            snackbar.showSnackbar(if (it == RouteViewModel.NO_LOCATION) noLocationMsg else it)
            viewModel.clearActionError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.loading -> EmptyState(
                icon = Icons.Filled.Route,
                title = stringResource(R.string.vol_route_title),
                description = null,
            )

            !state.hasRoute -> EmptyState(
                icon = Icons.Filled.Route,
                title = stringResource(R.string.vol_route_empty_title),
                description = stringResource(R.string.vol_route_empty_desc),
            )

            else -> {
                val points = state.route?.points.orEmpty()
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    item {
                        SectionHeader(title = stringResource(R.string.vol_route_title))
                        if (state.trackingError) {
                            Text(
                                text = stringResource(R.string.vol_route_tracking_error),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (state.trackingActive) {
                            Text(
                                text = stringResource(R.string.vol_route_tracking_on),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    items(points, key = { it.kind + (it.ticketId ?: -1).toString() }) { point ->
                        RoutePointCard(
                            point = point,
                            pickupBusy = state.pickupBusy,
                            busyTicketId = state.busyPointTicketId,
                            onPickup = viewModel::completePickup,
                            onScan = onScan,
                            onAttempt = viewModel::attemptDelivery,
                        )
                    }
                    item {
                        // Finishing is always allowed; the server releases any
                        // unfinished points back to the open queue.
                        SaveFoodButton(
                            text = stringResource(R.string.vol_route_finish),
                            loading = state.finishing,
                            onClick = { finishDialog = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (finishDialog) {
        VolunteerConfirmDialog(
            title = stringResource(R.string.vol_route_finish_confirm_title),
            text = stringResource(R.string.vol_route_finish_confirm_desc),
            confirmLabel = stringResource(R.string.vol_route_finish),
            onConfirm = {
                finishDialog = false
                viewModel.finishRoute(onFinished = {})
            },
            onDismiss = { finishDialog = false },
        )
    }
}

@Composable
private fun RoutePointCard(
    point: RoutePointDto,
    pickupBusy: Boolean,
    busyTicketId: Int?,
    onPickup: () -> Unit,
    onScan: (Int) -> Unit,
    onAttempt: (Int) -> Unit,
) {
    val isShop = point.kind == "shop"
    val done = point.done == true
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isShop) stringResource(R.string.vol_route_point_shop)
                    else stringResource(R.string.vol_route_point_delivery),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                if (done) {
                    StatusBadge(
                        text = if (point.released == true) stringResource(R.string.vol_route_released)
                        else stringResource(R.string.vol_route_delivered),
                        tone = if (point.released == true) BadgeTone.DANGER else BadgeTone.DONE,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            point.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            point.addrDetail?.takeIf { it.isNotBlank() }?.let {
                IconLine(it)
            }
            val attempts = point.attemptCount ?: 0
            if (!isShop && attempts > 0 && !done) {
                Text(
                    text = stringResource(R.string.vol_route_attempt_count, attempts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (!done) {
                // Hand navigation to a maps app: it knows the traffic, we don't.
                val lat = point.lat
                val lon = point.lon
                if (lat != null && lon != null) {
                    val context = LocalContext.current
                    SaveFoodOutlinedButton(
                        text = stringResource(R.string.vol_route_navigate_btn),
                        onClick = { Navigation.openRoute(context, listOf(lat to lon)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (isShop) {
                    SaveFoodButton(
                        text = stringResource(R.string.vol_route_pickup_btn),
                        loading = pickupBusy,
                        onClick = onPickup,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val ticketId = point.ticketId
                    val busy = busyTicketId != null && busyTicketId == ticketId
                    SaveFoodButton(
                        text = stringResource(R.string.vol_route_deliver_btn),
                        loading = busy,
                        enabled = ticketId != null,
                        onClick = { ticketId?.let(onScan) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SaveFoodOutlinedButton(
                        text = stringResource(R.string.vol_route_attempt_btn),
                        onClick = { ticketId?.let(onAttempt) },
                        enabled = ticketId != null && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun IconLine(text: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * Captures a delivery photo (CameraX) before confirming. The backend
 * complete_point endpoint has no photo field, so the captured image is local
 * proof only — see TODO(conductor) in the report. Confirmation still proceeds
 * on QR + GPS, which the server verifies.
 */
@Composable
private fun DeliveryPhotoScreen(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirming: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val granted by rememberCameraPermissionState()
    val imageCapture = rememberImageCapture()
    var captured by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.vol_route_photo_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (granted) {
            Box(
                modifier = Modifier.fillMaxWidth().height(360.dp),
                contentAlignment = Alignment.Center,
            ) {
                CameraPreview(imageCapture = imageCapture, modifier = Modifier.fillMaxSize())
                if (captured) {
                    Text(
                        text = "✓",
                        color = Color.White,
                        style = MaterialTheme.typography.displayLarge,
                    )
                }
            }
            SaveFoodOutlinedButton(
                text = if (captured) stringResource(R.string.vol_route_photo_retake)
                else stringResource(R.string.vol_route_photo_capture),
                leadingIcon = Icons.Filled.CameraAlt,
                onClick = {
                    scope.launch {
                        runCatching { imageCapture.captureToFile(context) }
                            .onSuccess { captured = true }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SaveFoodButton(
            text = stringResource(R.string.vol_route_photo_confirm),
            loading = confirming,
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        )
        SaveFoodOutlinedButton(
            text = stringResource(R.string.common_cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
