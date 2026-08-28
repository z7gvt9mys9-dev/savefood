package ru.savefood.app.feature.volunteer.rating

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.BadgeTone
import ru.savefood.app.core.designsystem.component.EmptyState
import ru.savefood.app.core.designsystem.component.SaveFoodButton
import ru.savefood.app.core.designsystem.component.SaveFoodCard
import ru.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import ru.savefood.app.core.designsystem.component.SectionHeader
import ru.savefood.app.core.designsystem.component.StatusBadge
import ru.savefood.app.feature.volunteer.data.StatsDto
import ru.savefood.app.feature.volunteer.data.TeamDto
import ru.savefood.app.feature.volunteer.data.ThanksDto

@Composable
fun RatingScreen(viewModel: RatingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.teamError) {
        state.teamError?.let {
            snackbar.showSnackbar(it)
            viewModel.clearTeamError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.error != null -> EmptyState(
                icon = Icons.Filled.EmojiEvents,
                title = stringResource(R.string.common_error_generic),
                description = state.error,
                actionLabel = stringResource(R.string.common_retry),
                onAction = viewModel::load,
            )
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader(title = stringResource(R.string.vol_rating_title))
                if (state.partialError) {
                    Text(
                        text = stringResource(R.string.common_partial_load_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.stats?.let { LevelCard(it) }
                state.stats?.let { AchievementsCard(it) }
                ThanksCard(state.thanks)
                TeamCard(
                    team = state.team,
                    busy = state.teamBusy,
                    onCreate = viewModel::createTeam,
                    onJoin = viewModel::joinTeam,
                    onLeave = viewModel::leaveTeam,
                )
                Box(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun LevelCard(stats: StatsDto) {
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.vol_rating_level, stats.level.code),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.vol_rating_points, formatPoints(stats.level.points)),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { stats.level.progress.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            val nextCode = stats.level.nextCode
            Text(
                if (nextCode == null) {
                    stringResource(R.string.vol_rating_level_max)
                } else {
                    stringResource(
                        R.string.vol_rating_next_level,
                        nextCode,
                        formatPoints(stats.level.pointsToNext),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val avg = stats.avgRating
            Text(
                if (avg != null) stringResource(R.string.vol_rating_avg, avg.toString(), stats.ratingCount)
                else stringResource(R.string.vol_rating_no_rating),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.vol_rating_deliveries, stats.totalDeliveries), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.vol_rating_routes, stats.totalRoutes), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.vol_rating_kg, formatKg(stats.totalKg)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementsCard(stats: StatsDto) {
    if (stats.achievements.isEmpty()) return
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.vol_rating_achievements),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.achievements.forEach { code ->
                    StatusBadge(text = achievementLabel(code), tone = BadgeTone.ACTIVE)
                }
            }
        }
    }
}

@Composable
private fun achievementLabel(code: String): String = when (code) {
    "first_delivery" -> stringResource(R.string.vol_ach_first_delivery)
    "kg_100" -> stringResource(R.string.vol_ach_kg_100)
    "night_courier" -> stringResource(R.string.vol_ach_night_courier)
    "sprinter" -> stringResource(R.string.vol_ach_sprinter)
    else -> code
}

@Composable
private fun ThanksCard(thanks: List<ThanksDto>) {
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.vol_rating_thanks_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (thanks.isEmpty()) {
                Text(
                    stringResource(R.string.vol_rating_thanks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                thanks.forEach { t ->
                    Column {
                        Text("★ ${t.rating ?: "-"}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        t.comment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamCard(
    team: TeamDto?,
    busy: Boolean,
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    SaveFoodCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.vol_rating_team_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (team != null) {
                Text(team.name, style = MaterialTheme.typography.titleMedium)
                team.members?.let { Text(stringResource(R.string.vol_rating_team_members, it), style = MaterialTheme.typography.bodyMedium) }
                Text(
                    stringResource(
                        R.string.vol_rating_team_stats,
                        team.deliveries ?: 0,
                        formatKg(team.kg ?: 0.0),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                team.joinCode?.let {
                    Text(stringResource(R.string.vol_rating_team_code, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                SaveFoodOutlinedButton(
                    text = stringResource(R.string.vol_rating_team_leave),
                    onClick = onLeave,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    stringResource(R.string.vol_rating_team_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.vol_rating_team_name_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                SaveFoodButton(
                    text = stringResource(R.string.vol_rating_team_create),
                    loading = busy,
                    enabled = name.isNotBlank(),
                    onClick = { onCreate(name.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text(stringResource(R.string.vol_rating_team_code_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                SaveFoodOutlinedButton(
                    text = stringResource(R.string.vol_rating_team_join),
                    onClick = { onJoin(code.trim()) },
                    enabled = !busy && code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatKg(kg: Double): String =
    if (kg == kg.toLong().toDouble()) kg.toLong().toString() else "%.1f".format(kg)

private fun formatPoints(points: Double): String =
    if (points == points.toLong().toDouble()) points.toLong().toString() else "%.1f".format(points)
