package ru.savefood.app.feature.needy

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.runtime.Composable
import ru.savefood.app.R
import ru.savefood.app.core.ui.RoleShell
import ru.savefood.app.core.ui.TabItem
import ru.savefood.app.feature.needy.find.FindFoodScreen
import ru.savefood.app.feature.needy.history.HistoryScreen
import ru.savefood.app.feature.needy.profile.NeedyProfileScreen
import ru.savefood.app.feature.needy.tracking.TrackingScreen

/** Needy role — focused on tracking an order a courier already picked up. */
@Composable
fun NeedyShell(initialRoute: String? = null, onInitialRouteHandled: () -> Unit = {}) {
    RoleShell(
        initialRoute = initialRoute,
        onInitialRouteHandled = onInitialRouteHandled,
        tabs = listOf(
            TabItem("needy/tickets", R.string.nav_needy_tickets, Icons.Filled.LocalShipping) {
                // The empty-state CTA hints to use the "Find food" bottom tab;
                // RoleShell owns the navController, so no programmatic tab switch.
                TrackingScreen(onGoFindFood = {})
            },
            TabItem("needy/find", R.string.nav_needy_find, Icons.Filled.Restaurant) {
                FindFoodScreen()
            },
            TabItem("needy/history", R.string.nav_history, Icons.Filled.History) {
                HistoryScreen()
            },
            TabItem("needy/profile", R.string.nav_profile, Icons.Filled.Person) {
                NeedyProfileScreen()
            },
        ),
    )
}
