package ru.savefood.app.feature.volunteer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.runtime.Composable
import ru.savefood.app.R
import ru.savefood.app.core.ui.RoleShell
import ru.savefood.app.core.ui.TabItem
import ru.savefood.app.feature.volunteer.available.AvailableScreen
import ru.savefood.app.feature.volunteer.profile.VolunteerProfileScreen
import ru.savefood.app.feature.volunteer.rating.RatingScreen
import ru.savefood.app.feature.volunteer.route.RouteScreen
/** Volunteer role — the initiator: picks up open requests and runs the route. */
@Composable
fun VolunteerShell(initialRoute: String? = null, onInitialRouteHandled: () -> Unit = {}) {
    RoleShell(
        initialRoute = initialRoute,
        onInitialRouteHandled = onInitialRouteHandled,
        tabs = listOf(
            TabItem("vol/available", R.string.nav_vol_available, Icons.Filled.Map) {
                AvailableScreen(onRouteStarted = {})
            },
            TabItem("vol/route", R.string.nav_vol_route, Icons.Filled.Route) {
                RouteScreen()
            },
            TabItem("vol/rating", R.string.nav_vol_rating, Icons.Filled.EmojiEvents) {
                RatingScreen()
            },
            TabItem("vol/profile", R.string.nav_profile, Icons.Filled.Person) {
                VolunteerProfileScreen()
            },
        ),
    )
}
