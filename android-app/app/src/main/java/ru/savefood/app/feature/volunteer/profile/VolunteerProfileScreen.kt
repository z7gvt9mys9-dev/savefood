package ru.savefood.app.feature.volunteer.profile
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.BadgeTone
import ru.savefood.app.core.designsystem.component.EmptyState
import ru.savefood.app.core.designsystem.component.LanguageSelectorCard
import ru.savefood.app.core.designsystem.component.ProfileSkeleton
import ru.savefood.app.core.designsystem.component.SaveFoodButton
import ru.savefood.app.core.designsystem.component.SaveFoodCard
import ru.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import ru.savefood.app.core.designsystem.component.SectionHeader
import ru.savefood.app.core.designsystem.component.StatusBadge
import ru.savefood.app.feature.volunteer.data.NotificationDto
import ru.savefood.app.feature.volunteer.data.RouteHistoryDto
@Composable
fun VolunteerProfileScreen(viewModel: VolunteerProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    val savedMsg = stringResource(R.string.vol_profile_saved)
    val uploadedMsg = stringResource(R.string.vol_profile_kyc_uploaded)
    val markReadErr = stringResource(R.string.common_error_generic)
    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.uploadDocument(uri, uploadedMsg) }
    val p = state.profile
    var name by remember(p) { mutableStateOf(p?.name.orEmpty()) }
    var contact by remember(p) { mutableStateOf(p?.contact.orEmpty()) }
    var city by remember(p) { mutableStateOf(p?.city.orEmpty()) }
    var thermalBag by remember(p) { mutableStateOf(p?.hasThermalBag == true) }
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
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader(title = stringResource(R.string.vol_profile_title))
            if (state.partialError) {
                Text(
                    text = stringResource(R.string.common_partial_load_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.vol_profile_section_personal), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.vol_profile_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = contact, onValueChange = { contact = it },
                        label = { Text(stringResource(R.string.vol_profile_contact)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = city, onValueChange = { city = it },
                        label = { Text(stringResource(R.string.vol_profile_city)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.vol_profile_thermal_bag), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.vol_profile_thermal_bag_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = thermalBag, onCheckedChange = { thermalBag = it })
                    }
                }
            }
            SaveFoodButton(
                text = stringResource(R.string.vol_profile_save),
                loading = state.saving,
                onClick = { viewModel.save(name, contact, city, thermalBag, savedMsg) },
                modifier = Modifier.fillMaxWidth(),
            )
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.vol_profile_section_kyc), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        KycBadge(p?.status)
                    }
                    Text(
                        stringResource(R.string.vol_profile_kyc_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SaveFoodOutlinedButton(
                        text = stringResource(R.string.vol_profile_kyc_upload),
                        leadingIcon = Icons.Filled.UploadFile,
                        onClick = { docPicker.launch("*/*") },
                        enabled = !state.uploadingDoc,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.vol_profile_section_history), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (state.history.isEmpty()) {
                        Text(
                            stringResource(R.string.vol_profile_history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.history.forEach { HistoryRow(it) }
                    }
                }
            }
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.vol_profile_section_notifications), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (state.notifications.isEmpty()) {
                        Text(
                            stringResource(R.string.vol_profile_notifications_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.notifications.forEach { note ->
                            NotificationRow(note, onRead = { viewModel.markNotificationRead(note.id, markReadErr) })
                        }
                    }
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
}
@Composable
private fun KycBadge(status: String?) {
    val (label, tone) = when (status) {
        "approved" -> stringResource(R.string.vol_profile_status_approved) to BadgeTone.DONE
        "rejected" -> stringResource(R.string.vol_profile_status_rejected) to BadgeTone.DANGER
        else -> stringResource(R.string.vol_profile_status_pending) to BadgeTone.PENDING
    }
    StatusBadge(text = label, tone = tone)
}
@Composable
private fun HistoryRow(route: RouteHistoryDto) {
    Column {
        Text(
            stringResource(R.string.vol_profile_route_n, route.id),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.vol_profile_route_points, route.points.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
@Composable
private fun NotificationRow(note: NotificationDto, onRead: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = note.payload ?: note.type.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = if ((note.read ?: 0) == 0) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        if ((note.read ?: 0) == 0) {
            TextButton(onClick = onRead) { Text(stringResource(R.string.vol_profile_mark_read)) }
        }
    }
}
