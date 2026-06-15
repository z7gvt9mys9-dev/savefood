package kz.savefood.app.feature.volunteer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.runtime.Composable
import kz.savefood.app.R
import kz.savefood.app.core.ui.RoleShell
import kz.savefood.app.core.ui.TabItem
import kz.savefood.app.feature.volunteer.available.AvailableScreen
import kz.savefood.app.feature.volunteer.profile.VolunteerProfileScreen
import kz.savefood.app.feature.volunteer.rating.RatingScreen
import kz.savefood.app.feature.volunteer.route.RouteScreen

/** Volunteer role — the initiator: picks up open requests and runs the route. */
@Composable
fun VolunteerShell() {
    RoleShell(
        tabs = listOf(
            TabItem("vol/available", R.string.nav_vol_available, Icons.Filled.Map) {
                // RoleShell owns the navController, so there is no programmatic tab
                // switch: after start_route the "My route" tab reloads the active
                // route whenever it is shown.
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
