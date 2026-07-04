package ru.savefood.app.core.push

import ru.savefood.app.core.datastore.UserRole

/**
 * Maps the backend's notification `data.url` (a web-style path such as `/`,
 * `/volunteer`, `/needy`) onto a bottom-nav tab route for the logged-in role.
 *
 * The url is carried verbatim as the [EXTRA_URL] intent extra:
 * - for app-foreground notifications we build, via the tap PendingIntent;
 * - for system-tray notifications (app backgrounded), FCM copies every `data`
 *   field — including `url` — onto the launch intent, so the same key works.
 */
object PushDeepLink {
    /** Intent extra holding the deep-link url. Matches FCM's `data` field name
     *  so the system-tray launch intent and our own PendingIntent align. */
    const val EXTRA_URL = "url"

    /**
     * Resolve the tab route to open for [role] given a notification [url], or
     * null to stay on the role's default tab. Notifications are keyed by
     * (role, related_id) server-side, so [url] always targets the current role.
     */
    fun tabRoute(role: UserRole, url: String?): String? {
        val path = url?.substringBefore('?')?.trimEnd('/').orEmpty()
        return when (role) {
            // Volunteer chat pings link to `/volunteer` → the active route, where
            // the courier⇄recipient conversation lives.
            UserRole.VOLUNTEER -> if (path.startsWith("/volunteer")) "vol/route" else null
            // Needy notifications (`/needy`, matched-lot `/`) → order tracking.
            UserRole.NEEDY -> if (path.startsWith("/needy") || path.isEmpty()) "needy/tickets" else null
            UserRole.SHOP -> null
            UserRole.ADMIN, UserRole.UNKNOWN -> null
        }
    }
}
