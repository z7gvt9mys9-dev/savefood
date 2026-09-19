package ru.savefood.app.feature.volunteer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.savefood.app.feature.volunteer.data.VolunteerRepository

@HiltViewModel
class VolunteerPresenceViewModel @Inject constructor(
    private val repository: VolunteerRepository,
) : ViewModel() {
    private var heartbeatJob: Job? = null

    fun start() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = viewModelScope.launch {
            val volunteerId = repository.currentVolunteerId() ?: return@launch
            while (isActive) {
                repository.heartbeat(volunteerId)
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
