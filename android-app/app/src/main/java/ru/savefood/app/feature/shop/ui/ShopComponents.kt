package ru.savefood.app.feature.shop.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.BadgeTone
import ru.savefood.app.core.designsystem.component.StatusBadge

/** Lot lifecycle status → localized badge (text + semantic tone). */
@Composable
fun LotStatusBadge(status: String?) {
    val (labelRes, tone) = when (status) {
        "active" -> R.string.shop_lot_status_active to BadgeTone.ACTIVE
        "taken" -> R.string.shop_lot_status_taken to BadgeTone.PENDING
        "confirmed" -> R.string.shop_lot_status_confirmed to BadgeTone.DONE
        "fulfilled" -> R.string.shop_lot_status_fulfilled to BadgeTone.DONE
        "expired" -> R.string.shop_lot_status_expired to BadgeTone.DANGER
        "cancelled" -> R.string.shop_lot_status_cancelled to BadgeTone.NEUTRAL
        else -> R.string.shop_lot_status_unknown to BadgeTone.NEUTRAL
    }
    StatusBadge(text = stringResource(labelRes), tone = tone)
}

/** Generic confirm/cancel dialog reused across the shop role. */
@Composable
fun ShopConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
