package nl.imreboersma.volti.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.annotation.StringRes
import nl.imreboersma.volti.R

/**
 * The languages I can pick for Volti: the system's, or one of its own. The same per-app language as Android's
 * Settings › Apps › Volti › Language, so the two never disagree. [tag]: null for the system's.
 */
enum class AppLanguage(val tag: String?, @StringRes val label: Int) {
    System(null, R.string.language_system),
    English("en", R.string.language_english),
    Dutch("nl", R.string.language_dutch),
}

/** The language picked for Volti, here or in Android's settings; [AppLanguage.System] when none is. */
fun appLanguage(context: Context): AppLanguage {
    val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
    if (locales.isEmpty) return AppLanguage.System
    return AppLanguage.entries.firstOrNull { it.tag == locales[0].language } ?: AppLanguage.System
}

/** Picks [language] for Volti: Android shows its screens in it at once (the activity is recreated). */
fun setAppLanguage(context: Context, language: AppLanguage) {
    context.getSystemService(LocaleManager::class.java).applicationLocales =
        language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
}
