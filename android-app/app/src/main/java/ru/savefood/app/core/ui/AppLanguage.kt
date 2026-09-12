package ru.savefood.app.core.ui
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ru.savefood.app.R
enum class AppLanguage(val tag: String, val labelRes: Int) {
    RU("ru", R.string.lang_ru),
    EN("en", R.string.lang_en);
    companion object {
        /** The active app language, with Russian as a fallback for unsupported locales. */
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            // When the user has not chosen an app-specific language yet, Android uses
            // the device locale. Reflect that in the selector instead of showing
            // Russian as selected while resources are still resolved in English.
            val tag = if (locales.isEmpty) {
                java.util.Locale.getDefault().language
            } else {
                locales[0]?.language
            }
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
