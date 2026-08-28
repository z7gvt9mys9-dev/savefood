package ru.savefood.app.feature.needy.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.savefood.app.R
import ru.savefood.app.feature.needy.data.TicketDto

class HistoryTicketActionsTest {

    @Test
    fun `assigned ticket shows the explicit cancellation action`() {
        val action = HistoryTicketActions.actionFor(ticket(status = "assigned"))

        assertEquals(HistoryTicketAction.CANCEL_ACTIVE_REQUEST, action)
        assertEquals(R.string.needy_history_cancel, action.labelResId)
        assertTrue(HistoryTicketActions.canCancel(ticket(status = "assigned")))
    }

    @Test
    fun `fulfilled ticket has no cancellation action`() {
        assertFalse(HistoryTicketActions.canCancel(ticket(status = "fulfilled")))
    }

    @Test
    fun `cancelled ticket has no cancellation action`() {
        assertFalse(HistoryTicketActions.canCancel(ticket(status = "cancelled")))
    }

    @Test
    fun `pressing cancel dispatches the ticket cancellation once`() {
        var calls = 0
        var cancelledTicketId: Int? = null

        HistoryTicketActions.runCancellation(ticket(id = 73, status = "assigned")) {
            calls++
            cancelledTicketId = it
        }

        assertEquals(1, calls)
        assertEquals(73, cancelledTicketId)
    }

    @Test
    fun `terminal tickets never dispatch cancellation`() {
        var calls = 0

        listOf("fulfilled", "cancelled", "expired", "deleted").forEach { status ->
            HistoryTicketActions.runCancellation(ticket(status = status)) { calls++ }
        }

        assertEquals(0, calls)
    }

    private fun ticket(id: Int = 1, status: String) = TicketDto(
        id = id,
        needyId = 9,
        status = status,
        createdAt = "2026-08-28T10:00:00Z",
    )
}
