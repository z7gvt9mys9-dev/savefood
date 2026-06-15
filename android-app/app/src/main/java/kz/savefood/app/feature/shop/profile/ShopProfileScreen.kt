package kz.savefood.app.feature.shop.profile

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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kz.savefood.app.R
import kz.savefood.app.core.designsystem.component.BadgeTone
import kz.savefood.app.core.designsystem.component.EmptyState
import kz.savefood.app.core.designsystem.component.LanguageSelectorCard
import kz.savefood.app.core.designsystem.component.ProfileSkeleton
import kz.savefood.app.core.designsystem.component.SaveFoodButton
import kz.savefood.app.core.designsystem.component.SaveFoodCard
import kz.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import kz.savefood.app.core.designsystem.component.SectionHeader
import kz.savefood.app.core.designsystem.component.StatusBadge
import kz.savefood.app.core.device.qr.QrScannerScreen
import kz.savefood.app.feature.shop.data.NotificationDto
import kz.savefood.app.feature.shop.data.PlanDto

@Composable
fun ShopProfileScreen(viewModel: ShopProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var scanning by remember { mutableStateOf(false) }

    val savedMsg = stringResource(R.string.shop_profile_saved)
    val pickupMsg = stringResource(R.string.shop_pickup_confirmed)
    val markReadErr = stringResource(R.string.common_error_generic)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (scanning) {
        Box(modifier = Modifier.fillMaxSize()) {
            QrScannerScreen(
                onResult = { code ->
                    scanning = false
                    viewModel.confirmSelfPickup(code, pickupMsg)
                },
                onCancel = { scanning = false },
            )
        }
        return
    }

    val shop = state.shop
    var name by remember(shop) { mutableStateOf(shop?.name.orEmpty()) }
    var contact by remember(shop) { mutableStateOf(shop?.contact.orEmpty()) }
    var city by remember(shop) { mutableStateOf(shop?.city.orEmpty()) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.loading && shop == null -> ProfileSkeleton()
            state.error != null && shop == null -> EmptyState(
                icon = Icons.Filled.Storefront,
                title = stringResource(R.string.common_error_generic),
                description = state.error,
                actionLabel = stringResource(R.string.common_retry),
                onAction = viewModel::load,
            )
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader(title = stringResource(R.string.shop_profile_title))

            if (state.partialError) {
                Text(
                    text = stringResource(R.string.common_partial_load_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // --- Shop data ---
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.shop_profile_section_data),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.shop_profile_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = contact, onValueChange = { contact = it },
                        label = { Text(stringResource(R.string.shop_profile_contact)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = city, onValueChange = { city = it },
                        label = { Text(stringResource(R.string.shop_profile_city)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SaveFoodButton(
                text = stringResource(R.string.common_save),
                loading = state.saving,
                onClick = { viewModel.save(name, contact, city, savedMsg) },
                modifier = Modifier.fillMaxWidth(),
            )

            // --- Plan ---
            state.plan?.let { PlanCard(it) }

            // --- Self-pickup ---
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.shop_profile_section_pickup),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.shop_pickup_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SaveFoodOutlinedButton(
                        text = stringResource(R.string.shop_pickup_scan),
                        leadingIcon = Icons.Filled.QrCodeScanner,
                        onClick = { scanning = true },
                        enabled = !state.confirmingPickup,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // --- Notifications ---
            SaveFoodCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.shop_profile_section_notifications),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.notifications.isEmpty()) {
                        Text(
                            stringResource(R.string.shop_notifications_empty),
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
private fun PlanCard(plan: PlanDto) {
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.shop_profile_section_plan),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusBadge(text = plan.label, tone = BadgeTone.ACTIVE)
            }
            val used = plan.lotsUsedThisMonth
            val limit = plan.monthlyLotLimit
            Text(
                text = if (limit != null) {
                    stringResource(R.string.shop_plan_quota, used, limit)
                } else {
                    stringResource(R.string.shop_plan_quota_unlimited, used)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.shop_plan_features,
                    if (plan.ocr) "OCR " else "",
                    if (plan.esg) "ESG " else "",
                    if (plan.api) "API" else "",
                ).trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            color = if (note.read == 0) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        if (note.read == 0) {
            TextButton(onClick = onRead) { Text(stringResource(R.string.shop_mark_read)) }
        }
    }
}
