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
import ru.savefood.app.core.device.camera.CapturedPhoto
import ru.savefood.app.core.device.camera.captureToFile
import ru.savefood.app.core.device.camera.rememberCameraPermissionState
import ru.savefood.app.core.device.camera.rememberImageCapture
import ru.savefood.app.core.device.location.rememberLocationPermissionState
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
    val locationPermission = rememberLocationPermissionState { granted ->
        // The tracking flow is intentionally not started until Android has
        // granted location. Reloading also restarts it for an active route.
        if (granted) viewModel.load()
    }

    // Reload whenever the tab is shown; tracking starts inside the VM on success.
    LaunchedEffect(Unit) { viewModel.load() }

    // Holds the QR scanned for the ticket awaiting photo confirmation.
    var pendingTicketId by remember { mutableStateOf<Int?>(null) }
    var pendingQr by remember { mutableStateOf<String?>(null) }

    NavHost(navController = nav, startDestination = ROUTE_MAIN) {
        composable(ROUTE_MAIN) {
            RouteMain(
                viewModel = viewModel,
                locationAllowed = locationPermission.isGranted,
                requestLocation = locationPermission::request,
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
                    onConfirm = { photoUri ->
                        viewModel.confirmDelivery(ticketId, qr, photoUri) {
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
    locationAllowed: Boolean,
    requestLocation: () -> Unit,
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
                val shopPoints = points.filter { it.kind == "shop" }
                val pickupDone = shopPoints.isNotEmpty() && shopPoints.all { it.done == true }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    item {
                        SectionHeader(title = stringResource(R.string.vol_route_title))
                        if (!locationAllowed) {
                            SaveFoodOutlinedButton(
                                text = stringResource(R.string.vol_route_location_permission),
                                onClick = requestLocation,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
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
                            pickupDone = pickupDone,
                            onPickup = {
                                if (locationAllowed) viewModel.completePickup() else requestLocation()
                            },
                            onScan = { ticketId ->
                                if (locationAllowed) onScan(ticketId) else requestLocation()
                            },
                            onAttempt = { ticketId ->
                                if (locationAllowed) viewModel.attemptDelivery(ticketId) else requestLocation()
                            },
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
    pickupDone: Boolean,
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
            point.address?.takeIf { it.isNotBlank() }?.let {
                IconLine(it)
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
                        leadingIcon = Icons.Filled.Route,
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
                    if (!pickupDone) {
                        Text(
                            text = stringResource(R.string.vol_route_pickup_first),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SaveFoodButton(
                        text = stringResource(R.string.vol_route_deliver_btn),
                        loading = busy,
                        enabled = ticketId != null && pickupDone,
                        onClick = { ticketId?.let(onScan) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SaveFoodOutlinedButton(
                        text = stringResource(R.string.vol_route_attempt_btn),
                        onClick = { ticketId?.let(onAttempt) },
                        enabled = ticketId != null && !busy && pickupDone,
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
 * Captures a delivery photo (CameraX) before confirming. The proof is uploaded
 * to private server storage; only a moderation-approved fulfilled delivery can
 * ever be exposed on the public impact feed.
 */
@Composable
private fun DeliveryPhotoScreen(
    onConfirm: (android.net.Uri) -> Unit,
    onCancel: () -> Unit,
    confirming: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val granted by rememberCameraPermissionState()
    val imageCapture = rememberImageCapture()
    var capturedPhoto by remember { mutableStateOf<CapturedPhoto?>(null) }

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
                if (capturedPhoto != null) {
                    Text(
                        text = "✓",
                        color = Color.White,
                        style = MaterialTheme.typography.displayLarge,
                    )
                }
            }
            SaveFoodOutlinedButton(
                text = if (capturedPhoto != null) stringResource(R.string.vol_route_photo_retake)
                else stringResource(R.string.vol_route_photo_capture),
                leadingIcon = Icons.Filled.CameraAlt,
                onClick = {
                    scope.launch {
                        runCatching { imageCapture.captureToFile(context) }
                            .onSuccess { capturedPhoto = it }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SaveFoodButton(
            text = stringResource(R.string.vol_route_photo_confirm),
            loading = confirming,
            enabled = capturedPhoto != null,
            onClick = { capturedPhoto?.let { onConfirm(it.uri) } },
            modifier = Modifier.fillMaxWidth(),
        )
        SaveFoodOutlinedButton(
            text = stringResource(R.string.common_cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
