package kz.savefood.app.feature.shop.receipts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kz.savefood.app.R
import kz.savefood.app.core.designsystem.component.BadgeTone
import kz.savefood.app.core.designsystem.component.EmptyState
import kz.savefood.app.core.designsystem.component.SaveFoodButton
import kz.savefood.app.core.designsystem.component.SaveFoodCard
import kz.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import kz.savefood.app.core.designsystem.component.SectionHeader
import kz.savefood.app.core.designsystem.component.ShimmerListItem
import kz.savefood.app.core.designsystem.component.StatusBadge
import kz.savefood.app.feature.shop.data.ReceiptDto
import kz.savefood.app.feature.shop.data.ReceiptLotDraftDto
import kz.savefood.app.feature.shop.data.ShopRepository

/**
 * "Receipts / ESG" tab: OCR receipt drafts → confirmed lots, plus the ESG
 * impact summary and a CSV export (tax helper). Forecast is intentionally absent.
 */
@Composable
fun ReceiptsScreen(viewModel: ReceiptsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmReceipt by remember { mutableStateOf<ReceiptDto?>(null) }

    val csvSavedMsg = stringResource(R.string.shop_esg_csv_saved)
    val csvFailedMsg = stringResource(R.string.shop_esg_csv_failed)
    val confirmedMsg = stringResource(R.string.shop_receipt_confirmed)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val receiptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.uploadReceipt(uri) }

    val csvExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) viewModel.exportCsv(uri, csvSavedMsg, csvFailedMsg) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader(title = stringResource(R.string.shop_esg_title)) }

            item {
                EsgCard(
                    state = state,
                    onExport = { csvExporter.launch("savefood_donations_12m.csv") },
                )
            }

            item {
                SaveFoodButton(
                    text = stringResource(R.string.shop_receipt_upload),
                    leadingIcon = Icons.Filled.UploadFile,
                    loading = state.uploading,
                    onClick = { receiptPicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Text(
                    stringResource(R.string.shop_receipts_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            when {
                state.loading -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { repeat(2) { ShimmerListItem() } }
                }
                state.receipts.isEmpty() -> item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.shop_receipts_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> items(state.receipts, key = { it.id }) { receipt ->
                    ReceiptCard(
                        receipt = receipt,
                        shopId = state.shopId,
                        imageLoader = viewModel.imageLoader,
                        onConfirm = { confirmReceipt = receipt },
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    confirmReceipt?.let { receipt ->
        ConfirmReceiptDialog(
            receipt = receipt,
            busy = state.busyReceiptId == receipt.id,
            onConfirm = { drafts, expiry, address, timeSlot ->
                viewModel.confirmReceipt(receipt.id, drafts, expiry, address, timeSlot, confirmedMsg) {
                    confirmReceipt = null
                }
            },
            onDismiss = { confirmReceipt = null },
        )
    }
}

@Composable
private fun EsgCard(state: ReceiptsUiState, onExport: () -> Unit) {
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.shop_esg_summary),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            when (val esg = state.esg) {
                EsgState.Locked -> Text(
                    stringResource(R.string.shop_esg_locked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EsgState.Error -> Text(
                    stringResource(R.string.shop_esg_load_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is EsgState.Loaded -> {
                    val t = esg.report.totals
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        EsgMetric(stringResource(R.string.shop_esg_kg), "${t.kg}")
                        EsgMetric(stringResource(R.string.shop_esg_co2), "${t.co2Kg}")
                        EsgMetric(stringResource(R.string.shop_esg_meals), "${t.meals}")
                        EsgMetric(stringResource(R.string.shop_esg_lots), "${t.lots}")
                    }
                    SaveFoodOutlinedButton(
                        text = stringResource(R.string.shop_esg_export_csv),
                        leadingIcon = Icons.Filled.Download,
                        onClick = onExport,
                        enabled = !state.exporting,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                EsgState.Loading -> Text(
                    stringResource(R.string.shop_esg_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EsgMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReceiptCard(
    receipt: ReceiptDto,
    shopId: Int?,
    imageLoader: coil.ImageLoader,
    onConfirm: () -> Unit,
) {
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = receipt.merchant ?: stringResource(R.string.shop_receipt_n, receipt.id),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                ReceiptStatusBadge(receipt)
            }

            if (shopId != null) {
                val request = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(ShopRepository.absoluteUrl("/shops/$shopId/receipts/${receipt.id}/image"))
                    .crossfade(true)
                    .build()
                AsyncImage(
                    model = request,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
                )
            }

            receipt.total?.let {
                Text(
                    stringResource(R.string.shop_receipt_total, "$it ${receipt.currency ?: ""}".trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.shop_receipt_items, receipt.items.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (receipt.status) {
                "parsed" -> SaveFoodButton(
                    text = stringResource(R.string.shop_receipt_confirm),
                    leadingIcon = Icons.AutoMirrored.Filled.ReceiptLong,
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                )
                "confirmed" -> Text(
                    stringResource(R.string.shop_receipt_lots_created, receipt.lotIds.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                "rejected" -> receipt.fraudReasons?.let {
                    Text(
                        stringResource(R.string.shop_receipt_rejected, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiptStatusBadge(receipt: ReceiptDto) {
    val (labelRes, tone) = when (receipt.status) {
        "parsed" -> R.string.shop_receipt_status_parsed to BadgeTone.PENDING
        "confirmed" -> R.string.shop_receipt_status_confirmed to BadgeTone.DONE
        "rejected" -> R.string.shop_receipt_status_rejected to BadgeTone.DANGER
        else -> R.string.shop_lot_status_unknown to BadgeTone.NEUTRAL
    }
    StatusBadge(text = stringResource(labelRes), tone = tone)
}

@Composable
private fun ConfirmReceiptDialog(
    receipt: ReceiptDto,
    busy: Boolean,
    onConfirm: (List<ReceiptLotDraftDto>, String?, String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val drafts = remember(receipt.id) { receipt.suggestedLots.toMutableStateList() }
    var expiry by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var timeSlot by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shop_receipt_confirm_title)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.shop_receipt_confirm_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(drafts.size) { idx ->
                    val draft = drafts[idx]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = draft.description,
                            onValueChange = { drafts[idx] = draft.copy(description = it) },
                            label = { Text(stringResource(R.string.shop_field_description)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draft.quantity.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.let { drafts[idx] = draft.copy(quantity = it) } },
                                label = { Text(stringResource(R.string.shop_field_quantity)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = draft.category,
                                onValueChange = { drafts[idx] = draft.copy(category = it) },
                                label = { Text(stringResource(R.string.shop_field_category)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = expiry, onValueChange = { expiry = it },
                        label = { Text(stringResource(R.string.shop_field_expiry)) },
                        placeholder = { Text("2026-06-30") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = address, onValueChange = { address = it },
                        label = { Text(stringResource(R.string.shop_field_address)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = timeSlot, onValueChange = { timeSlot = it },
                        label = { Text(stringResource(R.string.shop_field_time_slot)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && drafts.isNotEmpty() && drafts.all { it.description.isNotBlank() && it.quantity >= 1 },
                onClick = { onConfirm(drafts.toList(), expiry, address, timeSlot) },
            ) { Text(stringResource(R.string.shop_receipt_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
