package kz.savefood.app.feature.volunteer.rating

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kz.savefood.app.core.common.ApiResult
import kz.savefood.app.feature.volunteer.data.StatsDto
import kz.savefood.app.feature.volunteer.data.TeamDto
import kz.savefood.app.feature.volunteer.data.ThanksDto
import kz.savefood.app.feature.volunteer.data.VolunteerRepository
import javax.inject.Inject

data class RatingUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val stats: StatsDto? = null,
    val thanks: List<ThanksDto> = emptyList(),
    val team: TeamDto? = null,
    val teamBusy: Boolean = false,
    val teamError: String? = null,
)

@HiltViewModel
class RatingViewModel @Inject constructor(
    private val repo: VolunteerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RatingUiState())
    val state: StateFlow<RatingUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val volunteerId = repo.currentVolunteerId()
            if (volunteerId == null) {
                _state.update { it.copy(loading = false, error = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            when (val statsRes = repo.getStats(volunteerId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, stats = statsRes.data) }
                is ApiResult.Error -> {
                    _state.update { it.copy(loading = false, error = statsRes.message) }
                    return@launch
                }
            }
            (repo.getThanks(volunteerId) as? ApiResult.Success)?.let { res ->
                _state.update { it.copy(thanks = res.data) }
            }
            (repo.getTeam(volunteerId) as? ApiResult.Success)?.let { res ->
                _state.update { it.copy(team = res.data) }
            }
        }
    }

    fun createTeam(name: String) = teamAction { repo.createTeam(it, name) }
    fun joinTeam(code: String) = teamAction { repo.joinTeam(it, code) }

    fun leaveTeam() {
        viewModelScope.launch {
            val volunteerId = repo.currentVolunteerId() ?: return@launch
            _state.update { it.copy(teamBusy = true, teamError = null) }
            when (val res = repo.leaveTeam(volunteerId)) {
                is ApiResult.Success -> _state.update { it.copy(teamBusy = false, team = null) }
                is ApiResult.Error -> _state.update { it.copy(teamBusy = false, teamError = res.message) }
            }
        }
    }

    private fun teamAction(block: suspend (Int) -> ApiResult<TeamDto?>) {
        viewModelScope.launch {
            val volunteerId = repo.currentVolunteerId() ?: return@launch
            _state.update { it.copy(teamBusy = true, teamError = null) }
            when (val res = block(volunteerId)) {
                is ApiResult.Success -> _state.update { it.copy(teamBusy = false, team = res.data) }
                is ApiResult.Error -> _state.update { it.copy(teamBusy = false, teamError = res.message) }
            }
        }
    }

    fun clearTeamError() = _state.update { it.copy(teamError = null) }
}
