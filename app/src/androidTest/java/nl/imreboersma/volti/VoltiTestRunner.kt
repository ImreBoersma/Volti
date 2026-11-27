package nl.imreboersma.volti

import android.app.LocaleManager
import android.os.Bundle
import android.os.LocaleList
import androidx.test.runner.AndroidJUnitRunner

/**
 * The tests match on English text, so Volti runs in English whatever the device's language; the language picked
 * before (none, or one picked on the device) is back once they are done.
 */
class VoltiTestRunner : AndroidJUnitRunner() {
    private lateinit var before: LocaleList

    private val locales get() = targetContext.getSystemService(LocaleManager::class.java)

    override fun onStart() {
        before = locales.applicationLocales
        locales.applicationLocales = LocaleList.forLanguageTags("en")
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        locales.applicationLocales = before
        super.finish(resultCode, results)
    }
}
