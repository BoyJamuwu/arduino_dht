package com.example.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Gráfico de línea de una sola serie (sin librerías externas).
 * - Un gráfico por magnitud: temperatura y humedad nunca comparten eje.
 * - Línea de 2dp, marcadores de 8dp con anillo blanco, cuadrícula tenue.
 * - Etiqueta directa en el último valor; al tocar o arrastrar se muestra
 *   una guía vertical y el valor exacto del punto más cercano.
 */
class GraficoLineaView @JvmOverloads constructor(
    contexto: Context,
    attrs: AttributeSet? = null
) : View(contexto, attrs) {

    data class Punto(val etiqueta: String, val detalle: String, val valor: Double)

    private var puntos: List<Punto> = emptyList()
    private var unidad = ""
    private var seleccionado = -1

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private val pLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pPunto = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pAnillo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = color(R.color.superficie)
    }
    private val pGrilla = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = color(R.color.borde)
    }
    private val pGuia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = color(R.color.tinta_3)
    }
    private val pEje = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        color = color(R.color.tinta_3)
        typeface = Typeface.MONOSPACE
    }
    private val pValor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        color = color(R.color.tinta)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val pTooltip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.tinta) }
    private val pTooltipTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        color = color(R.color.white)
        typeface = Typeface.MONOSPACE
    }
    private val pVacio = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        color = color(R.color.tinta_3)
        textAlign = Paint.Align.CENTER
    }

    private val ruta = Path()
    private val area = Path()
    private val rect = RectF()

    fun mostrar(nuevos: List<Punto>, colorSerie: Int, unidadSerie: String) {
        puntos = nuevos
        unidad = unidadSerie
        seleccionado = -1
        pLinea.color = colorSerie
        pPunto.color = colorSerie
        pRelleno.color = (colorSerie and 0x00FFFFFF) or 0x14000000
        contentDescription = if (nuevos.isEmpty()) {
            "Gráfico sin registros"
        } else {
            "Gráfico de ${nuevos.size} registros. Último valor " +
                formato(nuevos.last().valor) + " $unidadSerie"
        }
        invalidate()
    }

    private fun formato(v: Double) = String.format(Locale.US, "%.1f", v)

    // Márgenes internos del área de dibujo
    private val izq get() = dp(36f)
    private val der get() = width - dp(12f)
    private val arr get() = dp(28f)
    private val aba get() = height - dp(24f)

    private fun xDe(i: Int): Float =
        if (puntos.size == 1) (izq + der) / 2f
        else izq + i * (der - izq) / (puntos.size - 1)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (puntos.isEmpty()) {
            canvas.drawText(
                "Sin registros todavía",
                width / 2f,
                height / 2f,
                pVacio
            )
            return
        }

        val minimo = puntos.minOf { it.valor }
        val maximo = puntos.maxOf { it.valor }
        val holgura = max((maximo - minimo) * 0.2, 1.0)
        val bajo = floor(minimo - holgura)
        val alto = ceil(maximo + holgura)
        val rango = alto - bajo

        fun yDe(v: Double) = (aba - (v - bajo) / rango * (aba - arr)).toFloat()

        // Cuadrícula: 3 líneas con su valor
        for (k in 0..2) {
            val v = bajo + rango * k / 2.0
            val y = yDe(v)
            canvas.drawLine(izq, y, der, y, pGrilla)
            canvas.drawText(String.format(Locale.US, "%.0f", v), 0f, y + pEje.textSize / 3, pEje)
        }

        // Etiquetas del eje X: primera, central y última
        val indicesX = buildSet {
            add(0)
            add(puntos.lastIndex)
            if (puntos.size >= 5) add(puntos.lastIndex / 2)
        }
        for (i in indicesX) {
            val texto = puntos[i].etiqueta
            val ancho = pEje.measureText(texto)
            val x = (xDe(i) - ancho / 2).coerceIn(izq - dp(4f), der - ancho)
            canvas.drawText(texto, x, height.toFloat() - dp(4f), pEje)
        }

        // Área y línea
        ruta.reset()
        area.reset()
        puntos.forEachIndexed { i, p ->
            val x = xDe(i)
            val y = yDe(p.valor)
            if (i == 0) {
                ruta.moveTo(x, y)
                area.moveTo(x, aba)
                area.lineTo(x, y)
            } else {
                ruta.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(xDe(puntos.lastIndex), aba)
        area.close()
        if (puntos.size > 1) {
            canvas.drawPath(area, pRelleno)
            canvas.drawPath(ruta, pLinea)
        }

        // Marcadores (solo si no saturan el gráfico)
        val conMarcadores = puntos.size <= 24
        puntos.forEachIndexed { i, p ->
            val esExtremo = i == puntos.lastIndex || i == seleccionado
            if (conMarcadores || esExtremo) {
                val x = xDe(i)
                val y = yDe(p.valor)
                val radio = if (i == seleccionado) dp(6f) else dp(4f)
                canvas.drawCircle(x, y, radio + dp(2f), pAnillo)
                canvas.drawCircle(x, y, radio, pPunto)
            }
        }

        if (seleccionado in puntos.indices) {
            dibujarSeleccion(canvas, yDe(puntos[seleccionado].valor))
        } else {
            // Etiqueta directa del último valor
            val ultimo = puntos.last()
            val texto = "${formato(ultimo.valor)} $unidad"
            val ancho = pValor.measureText(texto)
            val x = (xDe(puntos.lastIndex) - ancho).coerceAtLeast(izq)
            val y = (yDe(ultimo.valor) - dp(12f)).coerceAtLeast(pValor.textSize)
            canvas.drawText(texto, x, y, pValor)
        }
    }

    private fun dibujarSeleccion(canvas: Canvas, yPunto: Float) {
        val p = puntos[seleccionado]
        val x = xDe(seleccionado)

        canvas.drawLine(x, arr, x, aba, pGuia)

        val texto = "${p.detalle} · ${formato(p.valor)} $unidad"
        val ancho = pTooltipTexto.measureText(texto) + dp(16f)
        val altoCaja = pTooltipTexto.textSize + dp(12f)
        val izquierda = (x - ancho / 2).coerceIn(0f, width - ancho)
        val arriba = if (yPunto - altoCaja - dp(14f) > 0) yPunto - altoCaja - dp(14f) else 0f

        rect.set(izquierda, arriba, izquierda + ancho, arriba + altoCaja)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), pTooltip)
        canvas.drawText(
            texto,
            izquierda + dp(8f),
            arriba + altoCaja / 2 + pTooltipTexto.textSize / 3,
            pTooltipTexto
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (puntos.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val cercano = puntos.indices.minByOrNull { abs(xDe(it) - event.x) } ?: -1
                if (cercano != seleccionado) {
                    seleccionado = cercano
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
