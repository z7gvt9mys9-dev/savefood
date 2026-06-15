package kz.savefood.app.feature.shop.lots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.savefood.app.R
import kz.savefood.app.core.designsystem.component.EmptyState
import kz.savefood.app.core.designsystem.component.SaveFoodButton
import kz.savefood.app.core.designsystem.component.SaveFoodCard
import kz.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import kz.savefood.app.core.designsystem.component.SectionHeader
import kz.savefood.app.core.designsystem.component.ShimmerListItem
import kz.savefood.app.core.device.qr.QrImage
import kz.savefood.app.feature.shop.data.LotDto
import kz.savefood.app.feature.shop.data.LotUpdateDto
import kz.savefood.app.feature.shop.ui.LotStatusBadge
import kz.savefood.app.feature.shop.ui.ShopConfirmDialog

/**
 * "Lots" tab (shop start): active lots with edit/delete, QR-based transfer
 * confirmation for picked-up lots, and a history sub-tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotsScreen(viewModel: LotsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }

    var editLot by remember { mutableStateOf<LotDto?>(null) }
    var deleteLot by remember { mutableStateOf<LotDto?>(null) }
    var transferLot by remember { mutableStateOf<LotDto?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(tab) { if (tab == 1 && state.history.isEmpty()) viewModel.loadHistory() }

    val savedMsg = stringResource(R.string.shop_lot_saved)
    val deletedMsg = stringResource(R.string.shop_lot_deleted)
    val transferMsg = stringResource(R.string.shop_lot_transfer_done)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SectionHeader(title = stringResource(R.string.shop_lots_title))

            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.shop_lots_tab_active)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.shop_lots_tab_history)) })
            }

            if (tab == 0) {
                ActiveLots(
                    state = state,
                    onRetry = viewModel::load,
                    onEdit = { editLot = it },
                    onDelete = { deleteLot = it },
                    onTransfer = { transferLot = it },
                )
            } else {
                HistoryLots(state = state)
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    editLot?.let { lot ->
        EditLotDialog(
            lot = lot,
            saving = state.busyLotId == lot.id,
            onSave = { body -> viewModel.saveEdit(lot.id, body, savedMsg); editLot = null },
            onDismiss = { editLot = null },
        )
    }

    deleteLot?.let { lot ->
        ShopConfirmDialog(
            title = stringResource(R.string.shop_lot_delete_title),
            text = stringResource(R.string.shop_lot_delete_desc, lot.description ?: "#${lot.id}"),
            confirmLabel = stringResource(R.string.shop_lot_delete_confirm),
            onConfirm = { viewModel.deleteLot(lot.id, deletedMsg); deleteLot = null },
            onDismiss = { deleteLot = null },
        )
    }

    transferLot?.let { lot ->
        TransferQrDialog(
            lot = lot,
            confirming = state.busyLotId == lot.id,
            onConfirm = { viewModel.confirmTransfer(lot.id, transferMsg) { transferLot = null } },
            onDismiss = { transferLot = null },
        )
    }
}

@Composable
private fun ActiveLots(
    state: LotsUiState,
    onRetry: () -> Unit,
    onEdit: (LotDto) -> Unit,
    onDelete: (LotDto) -> Unit,
    onTransfer: (LotDto) -> Unit,
) {
    when {
        state.loading -> Column(
            modifier = Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { repeat(3) { ShimmerListItem() } }

        state.error != null -> EmptyState(
            icon = Icons.Filled.Inventory2,
            title = stringResource(R.string.common_error_generic),
            description = state.error,
            actionLabel = stringResource(R.string.common_retry),
            onAction = onRetry,
        )

        state.lots.isEmpty() -> EmptyState(
            icon = Icons.Filled.Inventory2,
            title = stringResource(R.string.shop_lots_empty_title),
            description = stringResource(R.string.shop_lots_empty_desc),
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(state.lots, key = { it.id }) { lot ->
                ShopLotCard(
                    lot = lot,
                    busy = state.busyLotId == lot.id,
                    onEdit = { onEdit(lot) },
                    onDelete = { onDelete(lot) },
                    onTransfer = { onTransfer(lot) },
                )
            }
        }
    }
}

@Composable
private fun HistoryLots(state: LotsUiState) {
    when {
        state.historyLoading -> Column(
            modifier = Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { repeat(3) { ShimmerListItem() } }

        state.history.isEmpty() -> EmptyState(
            icon = Icons.Filled.Inventory2,
            title = stringResource(R.string.shop_history_empty_title),
            description = stringResource(R.string.shop_history_empty_desc),
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(state.history, key = { it.id }) { lot ->
                ShopLotCard(lot = lot, busy = false, readOnly = true)
            }
        }
    }
}

@Composable
private fun ShopLotCard(
    lot: LotDto,
    busy: Boolean,
    readOnly: Boolean = false,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onTransfer: () -> Unit = {},
) {
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = lot.description ?: "#${lot.id}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                LotStatusBadge(lot.status)
            }
            val qty = lot.quantity?.let { q ->
                val n = if (q % 1.0 == 0.0) q.toInt().toString() else q.toString()
                "$n ${lot.unit ?: "кг"}".trim()
            }
            Text(
                text = listOfNotNull(lot.category, qty).joinToString(" · ").ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lot.expiryDate?.let {
                Text(
                    stringResource(R.string.shop_lot_expiry, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lot.takenBy?.let {
                Text(
                    stringResource(R.string.shop_lot_taken_by, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!readOnly) {
                if (lot.status == "taken") {
                    SaveFoodButton(
                        text = stringResource(R.string.shop_lot_transfer),
                        leadingIcon = Icons.Filled.QrCode2,
                        loading = busy,
                        onClick = onTransfer,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                if (lot.status == "active") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SaveFoodOutlinedButton(
                            text = stringResource(R.string.shop_lot_edit),
                            leadingIcon = Icons.Filled.Edit,
                            onClick = onEdit,
                            modifier = Modifier.weight(1f),
                        )
                        SaveFoodOutlinedButton(
                            text = stringResource(R.string.shop_lot_delete),
                            leadingIcon = Icons.Filled.Delete,
                            onClick = onDelete,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditLotDialog(
    lot: LotDto,
    saving: Boolean,
    onSave: (LotUpdateDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var description by remember { mutableStateOf(lot.description.orEmpty()) }
    var quantity by remember { mutableStateOf(lot.quantity?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
    var category by remember { mutableStateOf(lot.category.orEmpty()) }
    var address by remember { mutableStateOf(lot.address.orEmpty()) }
    var comment by remember { mutableStateOf(lot.comment.orEmpty()) }
    var requiresCold by remember { mutableStateOf(lot.requiresCold == true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shop_lot_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text(stringResource(R.string.shop_field_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = quantity, onValueChange = { quantity = it },
                    label = { Text(stringResource(R.string.shop_field_quantity)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category, onValueChange = { category = it },
                    label = { Text(stringResource(R.string.shop_field_category)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text(stringResource(R.string.shop_field_address)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.shop_field_comment)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.shop_field_requires_cold))
                    Switch(checked = requiresCold, onCheckedChange = { requiresCold = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && description.isNotBlank(),
                onClick = {
                    onSave(
                        LotUpdateDto(
                            description = description.trim(),
                            quantity = quantity.toDoubleOrNull(),
                            category = category.trim().ifBlank { null },
                            address = address.trim().ifBlank { null },
                            comment = comment.trim().ifBlank { null },
                            requiresCold = requiresCold,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun TransferQrDialog(
    lot: LotDto,
    confirming: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shop_lot_transfer_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.shop_lot_transfer_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                QrImage(
                    content = "SF-LOT-${lot.id}",
                    modifier = Modifier.size(200.dp),
                    contentDescription = stringResource(R.string.shop_lot_transfer_title),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !confirming, onClick = onConfirm) {
                Text(stringResource(R.string.shop_lot_transfer_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
