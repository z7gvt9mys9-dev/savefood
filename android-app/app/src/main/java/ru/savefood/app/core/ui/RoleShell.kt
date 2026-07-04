package ru.savefood.app.core.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/** One bottom-navigation tab and the screen it shows. */
data class TabItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val content: @Composable () -> Unit,
)

/**
 * Reusable role container: a Material 3 NavigationBar driving a nested NavHost.
 * Each role (shop / volunteer / needy) just supplies its list of [tabs].
 *
 * [initialRoute], when set, is a deep-link target (from a tapped notification):
 * the shell navigates to it once on entry, then calls [onInitialRouteHandled] so
 * the caller can clear the pending link.
 */
@Composable
fun RoleShell(
    tabs: List<TabItem>,
    initialRoute: String? = null,
    onInitialRouteHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    LaunchedEffect(initialRoute) {
        if (initialRoute != null && tabs.any { it.route == initialRoute }) {
            navController.navigate(initialRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        onInitialRouteHandled()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = tabs.first().route,
            modifier = Modifier.padding(innerPadding),
            // Gentle cross-fade between bottom-nav tabs — no directional slide,
            // since tabs are peers rather than a forward/back stack.
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            tabs.forEach { tab ->
                composable(tab.route) { tab.content() }
            }
        }
    }
}
