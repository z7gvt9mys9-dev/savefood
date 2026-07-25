package ru.savefood.app.core.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ru.savefood.app.R

/**
 * Supported in-app languages. The runtime switch is driven by AndroidX AppCompat's
 * per-app locale API (`AppCompatDelegate.setApplicationLocales`), which recreates
 * the activity and persists the choice (LocaleManager on API 33+, the AppCompat
 * autoStoreLocales service below that — see AndroidManifest). The set mirrors
 * `res/xml/locales_config.xml`.
 */
enum class AppLanguage(val tag: String, val labelRes: Int) {
    RU("ru", R.string.lang_ru),
    EN("en", R.string.lang_en);

    companion object {
        /** The active app language, falling back to Russian (the default catalog). */
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            val tag = if (locales.isEmpty) null else locales[0]?.language
            return entries.firstOrNull { it.tag == tag } ?: RU
        }

        /** Apply [language] app-wide; AppCompat recreates the activity to take effect. */
        fun apply(language: AppLanguage) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(language.tag),
            )
        }
    }
}
