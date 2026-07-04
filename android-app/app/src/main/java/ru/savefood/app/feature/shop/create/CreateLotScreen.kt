package ru.savefood.app.feature.shop.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.EmptyState
import ru.savefood.app.core.designsystem.component.SaveFoodButton
import ru.savefood.app.core.designsystem.component.SaveFoodCard
import ru.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import ru.savefood.app.core.designsystem.component.SectionHeader
import ru.savefood.app.core.device.camera.CameraPreview
import ru.savefood.app.core.device.camera.captureToFile
import ru.savefood.app.core.device.camera.rememberCameraPermissionState
import ru.savefood.app.core.device.camera.rememberImageCapture

/**
 * "Create" tab: a 3-step lot wizard — data → photo (camera/gallery) → review.
 * The new lot appears in the "Lots" tab (RoleShell owns the navController, so
 * there is no programmatic tab switch here).
 */
@Composable
fun CreateLotScreen(viewModel: CreateLotViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val createdMsg = stringResource(R.string.shop_create_success)

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    if (state.createdId != null) {
        CreatedState(onCreateAnother = { viewModel.reset() })
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SectionHeader(
                title = stringResource(
                    R.string.shop_create_step_of,
                    state.step + 1,
                    3,
                ),
            )
            when (state.step) {
                0 -> DataStep(viewModel)
                1 -> PhotoStep(viewModel)
                else -> ReviewStep(viewModel, createdMsg)
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun DataStep(viewModel: CreateLotViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val f = state.form
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = f.description, onValueChange = { v -> viewModel.updateForm { it.copy(description = v) } },
            label = { Text(stringResource(R.string.shop_field_description)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = f.quantity, onValueChange = { v -> viewModel.updateForm { it.copy(quantity = v) } },
                label = { Text(stringResource(R.string.shop_field_quantity)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = f.unit, onValueChange = { v -> viewModel.updateForm { it.copy(unit = v) } },
                label = { Text(stringResource(R.string.shop_field_unit)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        if (f.unit.isNotBlank() && f.unit != "кг") {
            OutlinedTextField(
                value = f.unitWeightKg, onValueChange = { v -> viewModel.updateForm { it.copy(unitWeightKg = v) } },
                label = { Text(stringResource(R.string.shop_field_unit_weight)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = f.category, onValueChange = { v -> viewModel.updateForm { it.copy(category = v) } },
            label = { Text(stringResource(R.string.shop_field_category)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = f.address, onValueChange = { v -> viewModel.updateForm { it.copy(address = v) } },
            label = { Text(stringResource(R.string.shop_field_address)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = f.timeSlot, onValueChange = { v -> viewModel.updateForm { it.copy(timeSlot = v) } },
            label = { Text(stringResource(R.string.shop_field_time_slot)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = f.expiryDate, onValueChange = { v -> viewModel.updateForm { it.copy(expiryDate = v) } },
            label = { Text(stringResource(R.string.shop_field_expiry)) },
            placeholder = { Text("2026-06-30") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.shop_field_requires_cold))
            Switch(checked = f.requiresCold, onCheckedChange = { c -> viewModel.updateForm { it.copy(requiresCold = c) } })
        }
        SaveFoodButton(
            text = stringResource(R.string.common_next),
            enabled = state.canContinue,
            onClick = { viewModel.setStep(1) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun PhotoStep(viewModel: CreateLotViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var useCamera by remember { mutableStateOf(false) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) { viewModel.setPhoto(uri); useCamera = false } }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.shop_create_photo_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (useCamera) {
            CameraCaptureBlock(
                onCaptured = { uri -> viewModel.setPhoto(uri); useCamera = false },
            )
        } else {
            state.photoUri?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SaveFoodOutlinedButton(
                    text = stringResource(R.string.shop_create_photo_camera),
                    leadingIcon = Icons.Filled.CameraAlt,
                    onClick = { useCamera = true },
                    modifier = Modifier.weight(1f),
                )
                SaveFoodOutlinedButton(
                    text = stringResource(R.string.shop_create_photo_gallery),
                    leadingIcon = Icons.Filled.PhotoLibrary,
                    onClick = { galleryPicker.launch("image/*") },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SaveFoodOutlinedButton(
                text = stringResource(R.string.common_back),
                onClick = { viewModel.setStep(0) },
                modifier = Modifier.weight(1f),
            )
            SaveFoodButton(
                text = stringResource(R.string.common_next),
                onClick = { viewModel.setStep(2) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CameraCaptureBlock(onCaptured: (android.net.Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val granted by rememberCameraPermissionState()
    val imageCapture = rememberImageCapture()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (granted) {
            Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                CameraPreview(imageCapture = imageCapture, modifier = Modifier.fillMaxSize())
            }
            SaveFoodButton(
                text = stringResource(R.string.shop_create_photo_capture),
                leadingIcon = Icons.Filled.CameraAlt,
                onClick = {
                    scope.launch {
                        runCatching { imageCapture.captureToFile(context) }
                            .onSuccess { onCaptured(it.uri) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                stringResource(R.string.shop_create_photo_permission),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReviewStep(viewModel: CreateLotViewModel, createdMsg: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val f = state.form
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SaveFoodCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReviewRow(stringResource(R.string.shop_field_description), f.description)
                ReviewRow(
                    stringResource(R.string.shop_field_quantity),
                    "${f.quantity} ${f.unit}".trim(),
                )
                if (f.category.isNotBlank()) ReviewRow(stringResource(R.string.shop_field_category), f.category)
                if (f.address.isNotBlank()) ReviewRow(stringResource(R.string.shop_field_address), f.address)
                if (f.timeSlot.isNotBlank()) ReviewRow(stringResource(R.string.shop_field_time_slot), f.timeSlot)
                if (f.expiryDate.isNotBlank()) ReviewRow(stringResource(R.string.shop_field_expiry), f.expiryDate)
                ReviewRow(
                    stringResource(R.string.shop_field_requires_cold),
                    if (f.requiresCold) stringResource(R.string.shop_review_yes) else stringResource(R.string.shop_review_no),
                )
                ReviewRow(
                    stringResource(R.string.shop_create_photo),
                    if (state.photoUri != null) stringResource(R.string.shop_review_yes) else stringResource(R.string.shop_review_no),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SaveFoodOutlinedButton(
                text = stringResource(R.string.common_back),
                onClick = { viewModel.setStep(1) },
                modifier = Modifier.weight(1f),
            )
            SaveFoodButton(
                text = stringResource(R.string.shop_create_submit),
                loading = state.submitting,
                enabled = state.canContinue,
                onClick = { viewModel.submit() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CreatedState(onCreateAnother: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Filled.CheckCircle,
            title = stringResource(R.string.shop_create_success),
            description = stringResource(R.string.shop_create_success_desc),
            actionLabel = stringResource(R.string.shop_create_another),
            onAction = onCreateAnother,
            modifier = Modifier.weight(1f),
        )
    }
}
