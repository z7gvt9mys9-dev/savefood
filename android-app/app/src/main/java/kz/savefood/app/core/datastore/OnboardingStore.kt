package kz.savefood.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Separate from the session store: app-level preferences that must survive logout
// (e.g. the first-run onboarding flag is per-install, not per-session).
private val Context.prefsDataStore by preferencesDataStore(name = "savefood_prefs")

/** Tracks whether the first-run onboarding has been completed for this install. */
@Singleton
class OnboardingStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    val completed: Flow<Boolean> = context.prefsDataStore.data.map { it[Keys.COMPLETED] ?: false }

    suspend fun setCompleted() {
        context.prefsDataStore.edit { it[Keys.COMPLETED] = true }
    }
}
