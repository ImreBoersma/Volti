package nl.imreboersma.volti

import android.app.LocaleManager
import android.os.Bundle
import android.os.LocaleList
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/**
 * The tests match on English text, so Volti runs in English whatever the device's language; the language picked
 * before (none, or one picked on the device) is back once they are done, also after a run that crashed. The one
 * from before is kept in a file until a run puts it back: a run after a killed one still knows it.
 */
class VoltiTestRunner : AndroidJUnitRunner() {
    private val locales get() = targetContext.getSystemService(LocaleManager::class.java)

    /** In the app under test's own files (the tests run in its process): only the debug app, never the release one. */
    private val before get() = File(targetContext.filesDir, "language-before-tests")

    override fun onStart() {
        if (!before.exists()) before.writeText(locales.applicationLocales.toLanguageTags())
        locales.applicationLocales = LocaleList.forLanguageTags("en")
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        putBack()
        super.finish(resultCode, results)
    }

    /** A crash ends the run without [finish]: the language goes back here too. */
    override fun onException(obj: Any?, e: Throwable?): Boolean {
        putBack()
        return super.onException(obj, e)
    }

    private fun putBack() {
        val file = before
        if (!file.exists()) return
        locales.applicationLocales = LocaleList.forLanguageTags(file.readText())
        file.delete()
    }
}
