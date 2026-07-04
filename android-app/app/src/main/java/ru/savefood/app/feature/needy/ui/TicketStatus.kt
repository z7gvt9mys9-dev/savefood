package ru.savefood.app.feature.needy.ui

import ru.savefood.app.core.designsystem.component.BadgeTone

/**
 * Recipient-facing tracking stages, derived from a ticket's backend status.
 *
 * Backend ticket statuses (see backend/needy): open, assigned, fulfilled,
 * cancelled. For delivery tracking we surface a 4-step ladder; self-pickup and
 * terminal states collapse onto it.
 */
enum class TrackStage(val step: Int) {
    WAITING(0),      // open — no courier yet
    ACCEPTED(1),     // assigned — courier took it
    ON_THE_WAY(2),   // assigned + has live location (en route)
    DELIVERED(3),    // fulfilled
    CANCELLED(-1);   // cancelled

    companion object {
        /** [hasLiveLocation] lets the tracking screen promote ASSIGNED → ON_THE_WAY. */
        fun from(status: String?, hasLiveLocation: Boolean = false): TrackStage = when (status) {
            "fulfilled" -> DELIVERED
            "cancelled" -> CANCELLED
            "assigned" -> if (hasLiveLocation) ON_THE_WAY else ACCEPTED
            else -> WAITING
        }
    }
}

/** Maps a backend ticket status to a design-system badge tone. */
fun badgeToneFor(status: String?): BadgeTone = when (status) {
    "fulfilled" -> BadgeTone.DONE
    "cancelled" -> BadgeTone.DANGER
    "assigned" -> BadgeTone.ACTIVE
    else -> BadgeTone.PENDING
}
