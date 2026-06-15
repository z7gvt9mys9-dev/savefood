package kz.savefood.app.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kz.savefood.app.R
import kz.savefood.app.core.designsystem.component.SaveFoodOutlinedButton

@Composable
fun ProfileScreen(
    roleLabel: String,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.nav_profile),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = roleLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Полный профиль появится в следующем шаге.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        SaveFoodOutlinedButton(
            text = stringResource(R.string.common_logout),
            onClick = viewModel::logout,
            leadingIcon = Icons.AutoMirrored.Filled.Logout,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
