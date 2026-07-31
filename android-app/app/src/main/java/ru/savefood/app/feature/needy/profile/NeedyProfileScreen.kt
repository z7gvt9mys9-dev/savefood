package ru.savefood.app.feature.needy.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.EmptyState
import ru.savefood.app.core.designsystem.component.LanguageSelectorCard
import ru.savefood.app.core.designsystem.component.ProfileSkeleton
import ru.savefood.app.core.designsystem.component.SaveFoodButton
import ru.savefood.app.core.designsystem.component.SaveFoodCard
import ru.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import ru.savefood.app.core.designsystem.component.SectionHeader
import ru.savefood.app.core.device.location.rememberLocationPermissionState
import ru.savefood.app.feature.needy.data.NeedyProfileUpdateDto
import ru.savefood.app.feature.needy.ui.ConfirmDialog

@Composable
fun NeedyProfileScreen(viewModel: NeedyProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val savedMsg = stringResource(R.string.needy_profile_saved)
    val uploadedMsg = stringResource(R.string.needy_profile_kyc_uploaded)
    val exportDoneMsg = stringResource(R.string.needy_profile_export_done)
    val exportFailedMsg = stringResource(R.string.needy_profile_export_failed)

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.uploadDocument(uri, uploadedMsg) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) viewModel.discardExport()
        else viewModel.saveExport(uri, exportDoneMsg, exportFailedMsg)
    }

    LaunchedEffect(state.exportedJson) {
        if (state.exportedJson != null) exportPicker.launch("savefood-account-export.json")
    }

    // Editable form fields, seeded from the loaded profile.
    val p = state.profile
    var familySize by remember(p) { mutableStateOf(p?.familySize?.toString().orEmpty()) }
    var preferences by remember(p) { mutableStateOf(p?.preferences.orEmpty()) }
    var urgency by remember(p) { mutableStateOf(p?.urgency.orEmpty()) }
    var city by remember(p) { mutableStateOf(p?.city.orEmpty()) }
    var address by remember(p) { mutableStateOf(p?.address.orEmpty()) }
    var apartment by remember(p) { mutableStateOf(p?.apartment.orEmpty()) }
    var floor by remember(p) { mutableStateOf(p?.floorNum.orEmpty()) }
    var entrance by remember(p) { mutableStateOf(p?.entrance.orEmpty()) }
    var availableTime by remember(p) { mutableStateOf(p?.availableTime.orEmpty()) }
    var profileLat by remember(p) { mutableStateOf(p?.lat) }
    var profileLon by remember(p) { mutableStateOf(p?.lon) }
    var addressChanged by remember(p) { mutableStateOf(false) }
    var locationChanged by remember(p) { mutableStateOf(false) }
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
                onResult = { lat, lon ->
                    profileLat = lat
                    profileLon = lon
                    locationChanged = true
                    locationError = false
                },
                onUnavailable = { locationError = true },
            )
        }
    }

    var deleteDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.loading && p == null -> ProfileSkeleton()
            state.error != null && p == null -> EmptyState(
                icon = Icons.Filled.Person,
                title = stringResource(R.string.common_error_generic),
                description = state.error,
                actionLabel = stringResource(R.string.common_retry),
                onAction = viewModel::load,
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader(title = stringResource(R.string.needy_profile_title))

            // --- Personal / family ---
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.needy_profile_section_personal), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = familySize, onValueChange = { familySize = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.needy_profile_family_size)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = preferences, onValueChange = { preferences = it },
                        label = { Text(stringResource(R.string.needy_profile_preferences)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = urgency, onValueChange = { urgency = it },
                        label = { Text(stringResource(R.string.needy_profile_urgency)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // --- Address ---
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.needy_profile_section_address), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = city, onValueChange = { city = it },
                        label = { Text(stringResource(R.string.needy_profile_city)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { value ->
                            address = value
                            addressChanged = value != p?.address.orEmpty()
                            if (addressChanged) {
                                // Never retain a coordinate belonging to an old
                                // address when the recipient edits it manually.
                                profileLat = null
                                profileLon = null
                                locationChanged = false
                                locationError = false
                            } else {
                                profileLat = p?.lat
                                profileLon = p?.lon
                            }
                        },
                        label = { Text(stringResource(R.string.needy_profile_address)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SaveFoodOutlinedButton(
                        text = stringResource(R.string.needy_profile_use_my_location),
                        leadingIcon = Icons.Filled.MyLocation,
                        onClick = {
                            locationError = false
                            if (locationPermission.isGranted) {
                                viewModel.fetchMyLocation(
                                    onResult = { lat, lon ->
                                        profileLat = lat
                                        profileLon = lon
                                        locationChanged = true
                                    },
                                    onUnavailable = { locationError = true },
                                )
                            } else {
                                fetchAfterPermission = true
                                locationPermission.request()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (profileLat != null && profileLon != null) {
                        Text(
                            text = stringResource(R.string.needy_profile_location_set),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else if (locationError) {
                        Text(
                            text = stringResource(R.string.needy_profile_location_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = apartment, onValueChange = { apartment = it },
                            label = { Text(stringResource(R.string.needy_profile_apartment)) },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = floor, onValueChange = { floor = it },
                            label = { Text(stringResource(R.string.needy_profile_floor)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = entrance, onValueChange = { entrance = it },
                        label = { Text(stringResource(R.string.needy_profile_entrance)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = availableTime, onValueChange = { availableTime = it },
                        label = { Text(stringResource(R.string.needy_profile_available_time)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SaveFoodButton(
                text = stringResource(R.string.needy_profile_save),
                loading = state.saving,
                onClick = {
                    viewModel.save(
                        NeedyProfileUpdateDto(
                            address = address.takeIf { it.isNotBlank() },
                            familySize = familySize.toIntOrNull(),
                            preferences = preferences.takeIf { it.isNotBlank() },
                            urgency = urgency.takeIf { it.isNotBlank() },
                            availableTime = availableTime.takeIf { it.isNotBlank() },
                            apartment = apartment.takeIf { it.isNotBlank() },
                            floorNum = floor.takeIf { it.isNotBlank() },
                            entrance = entrance.takeIf { it.isNotBlank() },
                            city = city.takeIf { it.isNotBlank() },
                            lat = if (addressChanged || locationChanged) profileLat else null,
                            lon = if (addressChanged || locationChanged) profileLon else null,
                            clearCoordinates = addressChanged && (profileLat == null || profileLon == null),
                        ),
                        savedMsg,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // --- KYC document (upload-only) ---
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.needy_profile_section_kyc), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.needy_profile_kyc_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.profile?.document != null) {
                        Text(
                            stringResource(R.string.needy_profile_kyc_uploaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    SaveFoodOutlinedButton(
                        text = stringResource(R.string.needy_profile_kyc_upload),
                        leadingIcon = Icons.Filled.UploadFile,
                        onClick = { docPicker.launch("*/*") },
                        enabled = !state.uploadingDoc,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // --- Privacy / geo-push / export / delete ---
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.needy_profile_section_privacy), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.needy_profile_geo_push), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.needy_profile_geo_push_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.profile?.geoPushEnabled ?: true,
                            onCheckedChange = viewModel::setGeoPush,
                        )
                    }
                    SaveFoodOutlinedButton(
                        text = stringResource(R.string.needy_profile_export),
                        leadingIcon = Icons.Filled.Download,
                        onClick = viewModel::export,
                        enabled = !state.exporting && state.exportedJson == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SaveFoodOutlinedButton(
                        text = stringResource(R.string.needy_profile_delete_account),
                        onClick = { deleteDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

                LanguageSelectorCard()

                SaveFoodOutlinedButton(
                    text = stringResource(R.string.common_logout),
                    leadingIcon = Icons.AutoMirrored.Filled.Logout,
                    onClick = viewModel::logout,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                )
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (deleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.needy_profile_delete_confirm_title),
            text = stringResource(R.string.needy_profile_delete_confirm_desc),
            confirmLabel = stringResource(R.string.needy_profile_delete_account),
            onConfirm = {
                viewModel.deleteAccount()
                deleteDialog = false
            },
            onDismiss = { deleteDialog = false },
        )
    }
}
