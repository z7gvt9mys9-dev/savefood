package ru.savefood.app.feature.onboarding
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.savefood.app.core.datastore.OnboardingStore
import javax.inject.Inject
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val store: OnboardingStore,
) : ViewModel() {
    /** null = not yet resolved (avoids flashing onboarding before the flag loads). */
    val completed: StateFlow<Boolean?> = store.completed
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )
    fun complete() {
        viewModelScope.launch { store.setCompleted() }
    }
}
