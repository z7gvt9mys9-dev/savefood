package ru.savefood.app.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// Brand palette — "rescue green" as the core, warm amber as accent for
// time-sensitive / expiring states. Kept in one place so light & dark schemes
// and status colors all derive from the same source.
internal val Green10 = Color(0xFF002111)
internal val Green20 = Color(0xFF00391F)
internal val Green30 = Color(0xFF00522F)
internal val Green40 = Color(0xFF1B9C5A) // primary brand
internal val Green80 = Color(0xFF7DDBA3)
internal val Green90 = Color(0xFF9CF8BF)

internal val Amber40 = Color(0xFFB8690C)
internal val Amber80 = Color(0xFFFFB871)
internal val Amber90 = Color(0xFFFFDCBE)

internal val Neutral10 = Color(0xFF191C1A)
internal val Neutral20 = Color(0xFF2E312F)
internal val Neutral90 = Color(0xFFE1E3DF)
internal val Neutral95 = Color(0xFFEFF1ED)
internal val Neutral99 = Color(0xFFFBFDF8)

internal val Red40 = Color(0xFFBA1A1A)
internal val Red80 = Color(0xFFFFB4AB)

// Semantic status colors used by StatusBadge across roles. Two tones each so
// they read on both light and dark surfaces.
object StatusColors {
    val PendingLight = Amber40
    val PendingDark = Amber80
    val ActiveLight = Green40
    val ActiveDark = Green80
    val DoneLight = Color(0xFF2E7D32)
    val DoneDark = Color(0xFF81C784)
    val DangerLight = Red40
    val DangerDark = Red80
    val NeutralLight = Color(0xFF5C5F5B)
    val NeutralDark = Color(0xFFBFC9C0)
}
