package ru.savefood.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.BrandLockup
import ru.savefood.app.core.designsystem.component.SaveFoodButton
import ru.savefood.app.core.designsystem.component.SaveFoodCard
@Composable
fun LoginScreen(
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val compactHeight = maxHeight < 720.dp
            val compactWidth = maxWidth < 380.dp
            val register = state.mode == AuthMode.REGISTER
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (compactWidth) 12.dp else 20.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(vertical = if (compactHeight || register) 12.dp else 24.dp),
                verticalArrangement = if (!compactHeight && !register) Arrangement.Center else Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandLockup(
                    markSize = if (compactHeight) 42.dp else 52.dp,
                    compact = compactHeight,
                    modifier = Modifier.padding(bottom = if (compactHeight) 12.dp else 20.dp),
                )
                SaveFoodCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 500.dp),
                ) {
                    ModeSelector(state.mode, viewModel::setMode)
                    Text(
                        text = stringResource(
                            if (register) R.string.auth_register_title else R.string.auth_title,
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                    Text(
                        text = stringResource(
                            if (register) R.string.auth_register_subtitle else R.string.auth_subtitle,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 18.dp),
                    )
                    if (register) {
                        RegistrationForm(
                            state = state,
                            compactWidth = compactWidth,
                            viewModel = viewModel,
                        )
                    } else {
                        LoginForm(state = state, viewModel = viewModel)
                    }
                    state.error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginForm(state: AuthUiState, viewModel: AuthViewModel) {
    AuthTextField(
        value = state.username,
        onValueChange = viewModel::onUsernameChange,
        label = stringResource(R.string.auth_username),
        keyboardType = KeyboardType.Text,
    )
    AuthTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = stringResource(R.string.auth_password),
        keyboardType = KeyboardType.Password,
        password = true,
        imeAction = ImeAction.Done,
        onDone = viewModel::login,
        modifier = Modifier.padding(top = 10.dp),
    )
    SaveFoodButton(
        text = stringResource(R.string.auth_sign_in),
        onClick = viewModel::login,
        loading = state.loading,
        enabled = state.username.isNotBlank() && state.password.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    )
}

@Composable
private fun RegistrationForm(
    state: AuthUiState,
    compactWidth: Boolean,
    viewModel: AuthViewModel,
) {
    RoleSelector(state.role, compactWidth, viewModel::selectRole)
    if (state.role == AuthRole.SHOP) {
        ChoiceSelector(
            labels = listOf(
                stringResource(R.string.auth_donor_business),
                stringResource(R.string.auth_donor_private),
            ),
            selectedIndex = if (state.donorKind == "business") 0 else 1,
            onSelected = { viewModel.onDonorKindChange(if (it == 0) "business" else "private") },
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    AuthTextField(
        value = state.name,
        onValueChange = viewModel::onNameChange,
        label = stringResource(R.string.auth_name),
        modifier = Modifier.padding(top = 12.dp),
    )
    if (state.role == AuthRole.SHOP) {
        AuthTextField(
            value = state.contact,
            onValueChange = viewModel::onContactChange,
            label = stringResource(R.string.auth_contact),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    AuthTextField(
        value = state.username,
        onValueChange = viewModel::onUsernameChange,
        label = stringResource(
            if (state.role == AuthRole.SHOP) R.string.auth_email else R.string.auth_phone,
        ),
        keyboardType = if (state.role == AuthRole.SHOP) KeyboardType.Email else KeyboardType.Phone,
        modifier = Modifier.padding(top = 10.dp),
    )
    if (state.role != AuthRole.NEEDY) {
        AuthTextField(
            value = state.city,
            onValueChange = viewModel::onCityChange,
            label = stringResource(R.string.auth_city_optional),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    AuthTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = stringResource(R.string.auth_password),
        keyboardType = KeyboardType.Password,
        password = true,
        modifier = Modifier.padding(top = 10.dp),
    )
    AuthTextField(
        value = state.confirmPassword,
        onValueChange = viewModel::onConfirmPasswordChange,
        label = stringResource(R.string.auth_confirm_password),
        keyboardType = KeyboardType.Password,
        password = true,
        imeAction = ImeAction.Done,
        onDone = viewModel::register,
        modifier = Modifier.padding(top = 10.dp),
    )
    if (state.password.isNotEmpty() && state.password.length < 8) {
        ValidationHint(stringResource(R.string.auth_password_min))
    } else if (state.confirmPassword.isNotEmpty() && state.password != state.confirmPassword) {
        ValidationHint(stringResource(R.string.auth_password_mismatch))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.onAgreedChange(!state.agreed) }
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.agreed, onCheckedChange = viewModel::onAgreedChange)
        Text(
            text = stringResource(R.string.auth_agree),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SaveFoodButton(
        text = stringResource(R.string.auth_create_account),
        onClick = viewModel::register,
        loading = state.loading,
        enabled = state.registrationValid,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
    )
}

@Composable
private fun ModeSelector(mode: AuthMode, onSelected: (AuthMode) -> Unit) {
    ChoiceSelector(
        labels = listOf(
            stringResource(R.string.auth_tab_login),
            stringResource(R.string.auth_tab_register),
        ),
        selectedIndex = mode.ordinal,
        onSelected = { onSelected(AuthMode.entries[it]) },
    )
}

@Composable
private fun RoleSelector(
    role: AuthRole,
    compactWidth: Boolean,
    onSelected: (AuthRole) -> Unit,
) {
    val labels = listOf(
        stringResource(R.string.role_shop),
        stringResource(R.string.role_volunteer),
        stringResource(R.string.role_needy_short),
    )
    Text(
        text = stringResource(R.string.auth_choose_role),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (compactWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AuthRole.entries.forEachIndexed { index, item ->
                ChoiceItem(
                    label = labels[index],
                    selected = item == role,
                    onClick = { onSelected(item) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AuthRole.entries.forEachIndexed { index, item ->
                ChoiceItem(
                    label = labels[index],
                    selected = item == role,
                    onClick = { onSelected(item) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ChoiceSelector(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            ChoiceItem(
                label = label,
                selected = index == selectedIndex,
                onClick = { onSelected(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChoiceItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ValidationHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 6.dp),
    )
}
