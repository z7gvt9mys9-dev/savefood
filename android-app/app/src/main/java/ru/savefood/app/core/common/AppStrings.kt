package ru.savefood.app.core.common

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

/** Resolves non-UI messages with the same per-app locale used by Compose/AppCompat. */
object AppStrings {
    @Volatile
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun get(@StringRes resource: Int, vararg args: Any): String {
        val base = checkNotNull(applicationContext) { "AppStrings is not initialized" }
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val context = if (tags.isBlank()) {
            base
        } else {
            val configuration = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tags))
            }
            base.createConfigurationContext(configuration)
        }
        return context.getString(resource, *args)
    }
}

class UserVisibleException(message: String) : IllegalStateException(message)
