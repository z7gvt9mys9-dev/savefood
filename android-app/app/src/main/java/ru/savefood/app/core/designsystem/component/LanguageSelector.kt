package ru.savefood.app.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.savefood.app.R
import ru.savefood.app.core.ui.AppLanguage

/**
 * Profile card to switch the app language at runtime. Picking a chip applies the
 * locale via [AppLanguage.apply], which recreates the activity; the new value is
 * re-derived from [AppLanguage.current] on the next composition.
 */
@Composable
fun LanguageSelectorCard(modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf(AppLanguage.current()) }

    SaveFoodCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.profile_language),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppLanguage.entries.forEach { language ->
                    val selected = language == current
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                current = language
                                AppLanguage.apply(language)
                            }
                        },
                        label = { Text(stringResource(language.labelRes)) },
                        leadingIcon = if (selected) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else null,
                    )
                }
            }
        }
    }
}
