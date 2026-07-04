package ru.savefood.app.feature.volunteer.route

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.core.device.location.LocationProvider
import ru.savefood.app.feature.volunteer.data.RouteDto
import ru.savefood.app.feature.volunteer.data.VolunteerRepository
import javax.inject.Inject

data class RouteUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val route: RouteDto? = null,
    val volunteerId: Int? = null,
    val busyPointTicketId: Int? = null,  // ticket point currently being completed/attempted
    val pickupBusy: Boolean = false,
    val finishing: Boolean = false,
    val actionError: String? = null,
    val trackingActive: Boolean = false,
    /** Set when location updates repeatedly fail to reach the server, so the
     *  recipient's live map is frozen. Cleared on the next successful push. */
    val trackingError: Boolean = false,
) {
    /** A real route exists only when the server returned an id (active_route is {} otherwise). */
    val hasRoute: Boolean get() = route?.id != null
}

@HiltViewModel
class RouteViewModel @Inject constructor(
    private val repo: VolunteerRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(RouteUiState())
    val state: StateFlow<RouteUiState> = _state.asStateFlow()

    private var trackingJob: Job? = null

    fun load() {
        viewModelScope.launch {
            val volunteerId = repo.currentVolunteerId()
            if (volunteerId == null) {
                _state.update { it.copy(loading = false, error = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null, volunteerId = volunteerId) }
            when (val res = repo.getActiveRoute(volunteerId)) {
                is ApiResult.Success -> {
                    val route = res.data
                    _state.update { it.copy(loading = false, route = route) }
                    if (route.id != null) startTracking(volunteerId) else stopTracking()
                }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = res.message) }
            }
        }
    }

    /** Periodically pushes the volunteer's location while a route is active (§27). */
    private fun startTracking(volunteerId: Int) {
        if (trackingJob?.isActive == true) return
        trackingJob = viewModelScope.launch {
            _state.update { it.copy(trackingActive = true, trackingError = false) }
            var consecutiveFailures = 0
            locationProvider.updates(TRACKING_INTERVAL_MS)
                .catch { e ->
                    // The location stream itself failed (e.g. updates couldn't be
                    // requested). Don't let the courier think tracking is live.
                    Log.w(TAG, "Location updates stream failed", e)
                    _state.update { it.copy(trackingActive = false, trackingError = true) }
                }
                .collect { loc ->
                    when (val res = repo.updateLocation(volunteerId, loc.latitude, loc.longitude)) {
                        is ApiResult.Success -> {
                            consecutiveFailures = 0
                            if (_state.value.trackingError) _state.update { it.copy(trackingError = false) }
                        }
                        is ApiResult.Error -> {
                            consecutiveFailures++
                            Log.w(TAG, "Location push failed ($consecutiveFailures): ${res.message}")
                            // Tolerate transient blips; surface only sustained failure so
                            // the recipient's live map being frozen doesn't go unnoticed.
                            if (consecutiveFailures >= MAX_TRACKING_FAILURES) {
                                _state.update { it.copy(trackingError = true) }
                            }
                        }
                    }
                }
        }
    }

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _state.update { it.copy(trackingActive = false, trackingError = false) }
    }

    /** Completes the shop pickup point. Requires the volunteer's current GPS fix. */
    fun completePickup() {
        val route = _state.value.route ?: return
        val routeId = route.id ?: return
        val volunteerId = _state.value.volunteerId ?: return
        viewModelScope.launch {
            _state.update { it.copy(pickupBusy = true, actionError = null) }
            val loc = locationProvider.lastLocation()
            if (loc == null) {
                _state.update { it.copy(pickupBusy = false, actionError = NO_LOCATION) }
                return@launch
            }
            val res = repo.completePoint(
                routeId = routeId,
                volunteerId = volunteerId,
                ticketId = null,
                lat = loc.latitude,
                lon = loc.longitude,
                qrCode = null,
            )
            _state.update { it.copy(pickupBusy = false) }
            when (res) {
                is ApiResult.Success -> load()
                is ApiResult.Error -> _state.update { it.copy(actionError = res.message) }
            }
        }
    }

    /**
     * Confirms a delivery: QR already scanned by the caller, GPS read here.
     * [onDone] is invoked on success so the screen can leave the QR/photo flow.
     */
    fun confirmDelivery(ticketId: Int, qrCode: String, onDone: () -> Unit) {
        val routeId = _state.value.route?.id ?: return
        val volunteerId = _state.value.volunteerId ?: return
        viewModelScope.launch {
            _state.update { it.copy(busyPointTicketId = ticketId, actionError = null) }
            val loc = locationProvider.lastLocation()
            if (loc == null) {
                _state.update { it.copy(busyPointTicketId = null, actionError = NO_LOCATION) }
                return@launch
            }
            val res = repo.completePoint(
                routeId = routeId,
                volunteerId = volunteerId,
                ticketId = ticketId,
                lat = loc.latitude,
                lon = loc.longitude,
                qrCode = qrCode,
            )
            _state.update { it.copy(busyPointTicketId = null) }
            when (res) {
                is ApiResult.Success -> { onDone(); load() }
                is ApiResult.Error -> _state.update { it.copy(actionError = res.message) }
            }
        }
    }

    /** Records a failed delivery attempt (no one answered). */
    fun attemptDelivery(ticketId: Int) {
        val routeId = _state.value.route?.id ?: return
        val volunteerId = _state.value.volunteerId ?: return
        viewModelScope.launch {
            _state.update { it.copy(busyPointTicketId = ticketId, actionError = null) }
            val res = repo.attemptDelivery(routeId, volunteerId, ticketId)
            _state.update { it.copy(busyPointTicketId = null) }
            when (res) {
                is ApiResult.Success -> load()
                is ApiResult.Error -> _state.update { it.copy(actionError = res.message) }
            }
        }
    }

    fun finishRoute(onFinished: () -> Unit) {
        val routeId = _state.value.route?.id ?: return
        val volunteerId = _state.value.volunteerId ?: return
        viewModelScope.launch {
            _state.update { it.copy(finishing = true, actionError = null) }
            val res = repo.finishRoute(routeId, volunteerId)
            _state.update { it.copy(finishing = false) }
            when (res) {
                is ApiResult.Success -> {
                    stopTracking()
                    _state.update { it.copy(route = RouteDto()) }
                    onFinished()
                }
                is ApiResult.Error -> _state.update { it.copy(actionError = res.message) }
            }
        }
    }

    fun clearActionError() = _state.update { it.copy(actionError = null) }

    override fun onCleared() {
        stopTracking()
        super.onCleared()
    }

    companion object {
        private const val TAG = "RouteViewModel"
        private const val TRACKING_INTERVAL_MS = 15_000L
        private const val MAX_TRACKING_FAILURES = 3
        const val NO_LOCATION = "no_location"
    }
}
