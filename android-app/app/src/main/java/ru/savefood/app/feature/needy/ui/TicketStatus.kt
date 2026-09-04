package ru.savefood.app.feature.needy.ui
import ru.savefood.app.core.designsystem.component.BadgeTone
enum class TrackStage(val step: Int) {
    WAITING(0),
    ACCEPTED(1),
    ON_THE_WAY(2),
    DELIVERED(3),
    CANCELLED(-1);
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
