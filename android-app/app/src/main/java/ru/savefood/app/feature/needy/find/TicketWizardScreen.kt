package ru.savefood.app.feature.needy.find
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.SaveFoodButton
import ru.savefood.app.core.designsystem.component.SaveFoodCard
import ru.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import ru.savefood.app.core.designsystem.component.SectionHeader
import ru.savefood.app.core.device.location.rememberLocationPermissionState
import ru.savefood.app.feature.needy.data.LotDto
import ru.savefood.app.feature.needy.data.TicketCreateDto
/** 3-step request wizard: method → details → confirm. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TicketWizardScreen(
    lot: LotDto?,
    viewModel: FindFoodViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(0) }
    var selfPickup by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(lot?.address.orEmpty()) }
    var apartment by remember { mutableStateOf("") }
    var floor by remember { mutableStateOf("") }
    var entrance by remember { mutableStateOf("") }
    var availableTime by remember { mutableStateOf("") }
    var items by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf<Double?>(null) }
    var lon by remember { mutableStateOf<Double?>(null) }
    var addressError by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf(false) }
    var fetchAfterPermission by remember { mutableStateOf(false) }
    val locationPermission = rememberLocationPermissionState { granted ->
        if (!granted && fetchAfterPermission) {
            fetchAfterPermission = false
            locationError = true
        }
    }
    LaunchedEffect(locationPermission.isGranted, fetchAfterPermission) {
        if (locationPermission.isGranted && fetchAfterPermission) {
            fetchAfterPermission = false
            viewModel.fetchMyLocation(
                onResult = { la, lo ->
                    lat = la
                    lon = lo
                    locationError = false
                },
                onUnavailable = { locationError = true },
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(title = stringResource(R.string.needy_wizard_title))
        LinearProgressIndicator(
            progress = { (step + 1) / 3f },
            modifier = Modifier.fillMaxWidth(),
        )
        lot?.let {
            SaveFoodCard(
                modifier = with(sharedScope) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "lot-${it.id}"),
                        animatedVisibilityScope = animatedScope,
                    )
                },
            ) {
                Text(stringResource(R.string.needy_wizard_lot), style = MaterialTheme.typography.labelMedium)
                Text(it.description ?: "#${it.id}", style = MaterialTheme.typography.titleMedium)
                it.shopName?.let { s -> Text(s, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        when (step) {
            0 -> MethodStep(
                selfPickup = selfPickup,
                lotMissing = lot == null,
                onSelect = { selectedSelfPickup ->
                    selfPickup = selectedSelfPickup
                    if (selectedSelfPickup) {
                        address = ""
                        apartment = ""
                        floor = ""
                        entrance = ""
                        lat = null
                        lon = null
                        addressError = false
                        locationError = false
                    }
                },
            )
            1 -> DetailsStep(
                selfPickup = selfPickup,
                address = address,
                onAddress = {
                    address = it
                    lat = null
                    lon = null
                    addressError = false
                    locationError = false
                },
                apartment = apartment, onApartment = { apartment = it },
                floor = floor, onFloor = { floor = it },
                entrance = entrance, onEntrance = { entrance = it },
                availableTime = availableTime, onAvailableTime = { availableTime = it },
                items = items, onItems = { items = it },
                addressError = addressError,
                locationError = locationError,
                onUseMyLocation = {
                    locationError = false
                    if (locationPermission.isGranted) {
                        viewModel.fetchMyLocation(
                            onResult = { la, lo -> lat = la; lon = lo },
                            onUnavailable = { locationError = true },
                        )
                    } else {
                        fetchAfterPermission = true
                        locationPermission.request()
                    }
                },
                hasLocation = lat != null && lon != null,
            )
            else -> ConfirmStep(
                selfPickup = selfPickup,
                address = address,
                availableTime = availableTime,
                items = items,
            )
        }
        state.submitError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SaveFoodOutlinedButton(
                text = if (step == 0) stringResource(R.string.common_cancel) else stringResource(R.string.common_back),
                onClick = { if (step == 0) onCancel() else step-- },
                modifier = Modifier.weight(1f),
            )
            SaveFoodButton(
                text = if (step < 2) stringResource(R.string.common_next) else stringResource(R.string.needy_wizard_submit),
                loading = state.submitting,
                onClick = {
                    when (step) {
                        0 -> step = 1
                        1 -> {
                            if (!selfPickup && (address.isBlank() || lat == null || lon == null)) {
                                addressError = address.isBlank()
                                locationError = lat == null || lon == null
                            } else step = 2
                        }
                        else -> {
                            val body = TicketCreateDto(
                                items = items.takeIf { it.isNotBlank() },
                                address = address.takeIf { it.isNotBlank() },
                                lat = lat,
                                lon = lon,
                                availableTime = availableTime.takeIf { it.isNotBlank() },
                                lotId = lot?.id,
                                apartment = apartment.takeIf { it.isNotBlank() },
                                floorNum = floor.takeIf { it.isNotBlank() },
                                entrance = entrance.takeIf { it.isNotBlank() },
                                selfPickup = selfPickup,
                            )
                            viewModel.submitTicket(body) { onDone() }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
@Composable
private fun MethodStep(selfPickup: Boolean, lotMissing: Boolean, onSelect: (Boolean) -> Unit) {
    Text(stringResource(R.string.needy_wizard_step_method), style = MaterialTheme.typography.titleMedium)
    MethodOption(
        selected = !selfPickup,
        title = stringResource(R.string.needy_wizard_method_delivery),
        desc = stringResource(R.string.needy_wizard_method_delivery_desc),
        onClick = { onSelect(false) },
    )
    MethodOption(
        selected = selfPickup,
        title = stringResource(R.string.needy_wizard_method_pickup),
        desc = stringResource(R.string.needy_wizard_method_pickup_desc),
        enabled = !lotMissing,
        onClick = { onSelect(true) },
    )
}
@Composable
private fun MethodOption(
    selected: Boolean,
    title: String,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SaveFoodCard(onClick = if (enabled) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
@Composable
private fun DetailsStep(
    selfPickup: Boolean,
    address: String, onAddress: (String) -> Unit,
    apartment: String, onApartment: (String) -> Unit,
    floor: String, onFloor: (String) -> Unit,
    entrance: String, onEntrance: (String) -> Unit,
    availableTime: String, onAvailableTime: (String) -> Unit,
    items: String, onItems: (String) -> Unit,
    addressError: Boolean,
    locationError: Boolean,
    onUseMyLocation: () -> Unit,
    hasLocation: Boolean,
) {
    Text(stringResource(R.string.needy_wizard_step_details), style = MaterialTheme.typography.titleMedium)
    if (!selfPickup) {
        OutlinedTextField(
            value = address, onValueChange = onAddress,
            label = { Text(stringResource(R.string.needy_wizard_address)) },
            isError = addressError,
            supportingText = if (addressError) {
                { Text(stringResource(R.string.needy_wizard_address_required)) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        SaveFoodOutlinedButton(
            text = stringResource(R.string.needy_wizard_use_my_location),
            onClick = onUseMyLocation,
            leadingIcon = Icons.Filled.MyLocation,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasLocation) {
            Text(
                "✓ ${stringResource(R.string.needy_wizard_use_my_location)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (locationError) {
            Text(
                stringResource(R.string.needy_wizard_location_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = apartment, onValueChange = onApartment,
                label = { Text(stringResource(R.string.needy_wizard_apartment)) },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = floor, onValueChange = onFloor,
                label = { Text(stringResource(R.string.needy_wizard_floor)) },
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = entrance, onValueChange = onEntrance,
            label = { Text(stringResource(R.string.needy_wizard_entrance)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = availableTime, onValueChange = onAvailableTime,
        label = { Text(stringResource(R.string.needy_wizard_available_time)) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = items, onValueChange = onItems,
        label = { Text(stringResource(R.string.needy_wizard_items)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}
@Composable
private fun ConfirmStep(
    selfPickup: Boolean,
    address: String,
    availableTime: String,
    items: String,
) {
    Text(stringResource(R.string.needy_wizard_step_confirm), style = MaterialTheme.typography.titleMedium)
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ConfirmRow(
                stringResource(R.string.needy_wizard_step_method),
                if (selfPickup) stringResource(R.string.needy_wizard_method_pickup)
                else stringResource(R.string.needy_wizard_method_delivery),
            )
            if (!selfPickup && address.isNotBlank()) {
                ConfirmRow(stringResource(R.string.needy_wizard_address), address)
            }
            if (availableTime.isNotBlank()) {
                ConfirmRow(stringResource(R.string.needy_wizard_available_time), availableTime)
            }
            if (items.isNotBlank()) {
                ConfirmRow(stringResource(R.string.needy_wizard_items), items)
            }
        }
    }
}
@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
