package nl.imreboersma.volti.annotate

import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import nl.imreboersma.volti.R

/** The marks I write most. Drawn by Volti itself, so they look the same whatever fonts the device has. */
enum class Symbol(@StringRes val label: Int) {
    Breath(R.string.symbol_breath),
    Fermata(R.string.symbol_fermata),
    Glasses(R.string.symbol_glasses),
    PP(R.string.symbol_pp),
    P(R.string.symbol_p),
    MP(R.string.symbol_mp),
    MF(R.string.symbol_mf),
    F(R.string.symbol_f),
    FF(R.string.symbol_ff),
    Sharp(R.string.symbol_sharp),
    Flat(R.string.symbol_flat),
    Natural(R.string.symbol_natural),
    ;

    val dynamic get() = this in DYNAMICS

    companion object {
        private val DYNAMICS = setOf(PP, P, MP, MF, F, FF)

        /** A stored name, or null for one this version doesn't know (left out, not shown wrongly). */
        fun named(name: String) = entries.firstOrNull { it.name == name }
    }
}

/** How tall a symbol is on the page, in page units: about 7 mm on an A4 part, a little over a staff. */
const val SYMBOL_SIZE = 32f

// Made when first drawn: a file that holds Android objects from the start can't be loaded in a plain unit test.
private val dynamicsPaint by lazy {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        textAlign = Paint.Align.CENTER
    }
}
private val accidentalPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }

/** [symbol], [size] tall, centred on [center], in [color]. Used on the page and in the palette alike. */
fun DrawScope.drawSymbol(symbol: Symbol, center: Offset, size: Float, color: Color) {
    val (cx, cy) = center
    val line = Stroke(width = size * 0.1f, cap = StrokeCap.Round)
    fun text(value: String, paint: Paint, height: Float) = drawIntoCanvas { canvas ->
        paint.color = color.toArgb()
        paint.textSize = height
        canvas.nativeCanvas.drawText(value, cx, cy + height * 0.35f, paint)
    }
    when (symbol) {
        Symbol.Breath -> {
            // A comma: a round head with a tail curling down to the left.
            drawCircle(color, radius = size * 0.16f, center = Offset(cx, cy - size * 0.12f))
            val tail = Path().apply {
                moveTo(cx + size * 0.14f, cy - size * 0.1f)
                quadraticTo(cx + size * 0.12f, cy + size * 0.25f, cx - size * 0.16f, cy + size * 0.38f)
            }
            drawPath(tail, color, style = line)
        }
        Symbol.Fermata -> {
            drawArc(
                color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(cx - size * 0.5f, cy - size * 0.35f), size = Size(size, size * 0.8f), style = line,
            )
            drawCircle(color, radius = size * 0.09f, center = Offset(cx, cy + size * 0.12f))
        }
        Symbol.Glasses -> {
            val r = size * 0.2f
            drawCircle(color, radius = r, center = Offset(cx - size * 0.27f, cy), style = line)
            drawCircle(color, radius = r, center = Offset(cx + size * 0.27f, cy), style = line)
            drawArc(
                color, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - size * 0.1f, cy - size * 0.1f), size = Size(size * 0.2f, size * 0.2f), style = line,
            )
        }
        Symbol.Sharp -> text("♯", accidentalPaint, size)
        // The system font draws these two smaller than the sharp.
        Symbol.Flat -> text("♭", accidentalPaint, size * 1.4f)
        Symbol.Natural -> text("♮", accidentalPaint, size * 1.3f)
        else -> text(symbol.name.lowercase(), dynamicsPaint, size * 0.9f) // The dynamics: as printed, bold italic serif.
    }
}
