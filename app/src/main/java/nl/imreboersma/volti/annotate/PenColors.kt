package nl.imreboersma.volti.annotate

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.imreboersma.volti.R
import nl.imreboersma.volti.data.dataStore

/**
 * The pen's colours, each with a name for TalkBack (android.md: colour is never the only signal). Each is dark
 * enough to read on white paper and on a cream scan. A mark stores its colour as ARGB, so a colour taken out of this
 * list later still draws as it was.
 */
enum class PenColor(val argb: Int, @StringRes val label: Int) {
    Black(0xFF0F0F0F.toInt(), R.string.colour_black),
    Red(0xFFC83823.toInt(), R.string.colour_red),
    Blue(0xFF2568BA.toInt(), R.string.colour_blue),
    Green(0xFF2E7D32.toInt(), R.string.colour_green),
    Orange(0xFFC45100.toInt(), R.string.colour_orange),
    Purple(0xFF6A1B9A.toInt(), R.string.colour_purple),
    ;

    companion object {
        fun of(argb: Int) = entries.firstOrNull { it.argb == argb }
    }
}

private val PEN_COLOR = intPreferencesKey("pen_color")

/** The colour the pen had last: the next score starts with it, also after a restart. A setting of the pen's, kept in DataStore. */
class PenColorStore(context: Context) {
    private val store = context.applicationContext.dataStore

    suspend fun last(): PenColor = PenColor.of(store.data.first()[PEN_COLOR] ?: PenColor.Black.argb) ?: PenColor.Black

    /** Kept also when the reader closes right after: in a scope of its own. */
    fun keep(color: PenColor) {
        scope.launch { store.edit { it[PEN_COLOR] = color.argb } }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
