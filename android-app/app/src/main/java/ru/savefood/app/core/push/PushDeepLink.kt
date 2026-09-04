package ru.savefood.app.core.push
import ru.savefood.app.core.datastore.UserRole
object PushDeepLink {
    const val EXTRA_URL = "url"
    fun tabRoute(role: UserRole, url: String?): String? {
        val path = url?.substringBefore('?')?.trimEnd('/').orEmpty()
        return when (role) {
            UserRole.VOLUNTEER -> if (path.startsWith("/volunteer")) "vol/route" else null
            UserRole.NEEDY -> if (path.startsWith("/needy") || path.isEmpty()) "needy/tickets" else null
            UserRole.SHOP -> null
            UserRole.ADMIN, UserRole.UNKNOWN -> null
        }
    }
}
