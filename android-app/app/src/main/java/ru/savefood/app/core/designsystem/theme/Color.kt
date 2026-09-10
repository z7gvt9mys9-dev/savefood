package ru.savefood.app.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/** Palette shared with the web client's Ember theme. */
internal val Ink = Color(0xFF100E0C)
internal val InkRaised = Color(0xFF171310)
internal val InkSoft = Color(0xFF201A15)
internal val InkWarm = Color(0xFF2A1E17)
internal val Paper = Color(0xFFFFF6E9)
internal val Cream = Color(0xFFFFF0D9)
internal val Muted = Color(0xFFBCB1A4)
internal val MutedStrong = Color(0xFFD5C9BB)

internal val Ember = Color(0xFFF46F35)
internal val EmberBright = Color(0xFFFF8A4F)
internal val EmberDeep = Color(0xFFB9471F)
internal val Honey = Color(0xFFF4C660)
internal val Sage = Color(0xFF9BBD89)
internal val Danger = Color(0xFFFF776C)

object StatusColors {
    val Pending = Honey
    val Active = EmberBright
    val Done = Sage
    val Danger = ru.savefood.app.core.designsystem.theme.Danger
    val Neutral = Muted
}
