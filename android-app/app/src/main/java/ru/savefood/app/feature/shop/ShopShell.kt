package ru.savefood.app.feature.shop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import ru.savefood.app.R
import ru.savefood.app.core.ui.RoleShell
import ru.savefood.app.core.ui.TabItem
import ru.savefood.app.feature.shop.create.CreateLotScreen
import ru.savefood.app.feature.shop.lots.LotsScreen
import ru.savefood.app.feature.shop.profile.ShopProfileScreen
import ru.savefood.app.feature.shop.receipts.ReceiptsScreen

/** Shop role — publish lots, confirm transfers, receipts/ESG (no forecast). */
@Composable
fun ShopShell(initialRoute: String? = null, onInitialRouteHandled: () -> Unit = {}) {
    RoleShell(
        initialRoute = initialRoute,
        onInitialRouteHandled = onInitialRouteHandled,
        tabs = listOf(
            TabItem("shop/lots", R.string.nav_shop_lots, Icons.Filled.Inventory2) {
                LotsScreen()
            },
            TabItem("shop/create", R.string.nav_shop_create, Icons.Filled.AddBox) {
                CreateLotScreen()
            },
            TabItem("shop/esg", R.string.nav_shop_esg, Icons.AutoMirrored.Filled.ReceiptLong) {
                ReceiptsScreen()
            },
            TabItem("shop/profile", R.string.nav_profile, Icons.Filled.Person) {
                ShopProfileScreen()
            },
        ),
    )
}
