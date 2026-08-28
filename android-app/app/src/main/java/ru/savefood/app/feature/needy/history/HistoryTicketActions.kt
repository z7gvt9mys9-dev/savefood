package ru.savefood.app.feature.needy.history

import androidx.annotation.StringRes
import ru.savefood.app.R
import ru.savefood.app.feature.needy.data.TicketDto

internal enum class HistoryTicketAction(@StringRes val labelResId: Int?) {
    CANCEL_ACTIVE_REQUEST(R.string.needy_history_cancel),
    NONE(null),
}

/**
 * Mirrors the backend cancellation contract: only open and assigned tickets
 * can be cancelled by a recipient.
 */
internal object HistoryTicketActions {
    private val cancellableStatuses = setOf("open", "assigned")

    fun actionFor(ticket: TicketDto): HistoryTicketAction =
        if (ticket.status in cancellableStatuses) HistoryTicketAction.CANCEL_ACTIVE_REQUEST
        else HistoryTicketAction.NONE

    fun canCancel(ticket: TicketDto): Boolean =
        actionFor(ticket) == HistoryTicketAction.CANCEL_ACTIVE_REQUEST

    fun runCancellation(ticket: TicketDto, cancel: (ticketId: Int) -> Unit) {
        if (canCancel(ticket)) cancel(ticket.id)
    }
}
