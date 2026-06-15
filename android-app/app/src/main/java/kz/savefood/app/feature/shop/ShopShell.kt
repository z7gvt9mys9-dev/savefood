package kz.savefood.app.feature.shop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import kz.savefood.app.R
import kz.savefood.app.core.ui.RoleShell
import kz.savefood.app.core.ui.TabItem
import kz.savefood.app.feature.shop.create.CreateLotScreen
import kz.savefood.app.feature.shop.lots.LotsScreen
import kz.savefood.app.feature.shop.profile.ShopProfileScreen
import kz.savefood.app.feature.shop.receipts.ReceiptsScreen

/** Shop role — publish lots, confirm transfers, receipts/ESG (no forecast). */
@Composable
fun ShopShell() {
    RoleShell(
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
