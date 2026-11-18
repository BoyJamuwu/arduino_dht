package com.example.test

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Historial guardado en SQLite: tabla con los últimos 10 registros y dos gráficos
 * (temperatura y humedad por separado). "Mostrar más" carga de a 10 registros.
 */
class HistorialActivity : BaseActivity() {

    companion object {
        private const val PAGINA = 10
    }

    private lateinit var db: LecturasDb
    private lateinit var contenedorFilas: LinearLayout
    private lateinit var tvVacio: TextView
    private lateinit var tvMostrando: TextView
    private lateinit var tvResumen: TextView
    private lateinit var vPuntoIntegridad: View
    private lateinit var btnMostrarMas: MaterialButton
    private lateinit var graficoTemperatura: GraficoLineaView
    private lateinit var graficoHumedad: GraficoLineaView

    private val lector = Executors.newSingleThreadExecutor()
    private val registros = mutableListOf<LecturasDb.Registro>()
    private var total = 0L

    private val formatoFecha = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val formatoHora = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val alCambiarDb: () -> Unit = { recargar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configurarPantalla(R.layout.activity_historial, "05 · Historial")

        db = LecturasDb.get(this)
        contenedorFilas = findViewById(R.id.contenedorFilas)
        tvVacio = findViewById(R.id.tvVacio)
        tvMostrando = findViewById(R.id.tvMostrando)
        tvResumen = findViewById(R.id.tvResumen)
        vPuntoIntegridad = findViewById(R.id.vPuntoIntegridad)
        btnMostrarMas = findViewById(R.id.btnMostrarMas)
        graficoTemperatura = findViewById(R.id.graficoTemperatura)
        graficoHumedad = findViewById(R.id.graficoHumedad)

        btnMostrarMas.setOnClickListener { cargarMas() }
    }

    override fun onResume() {
        super.onResume()
        db.agregarOyente(alCambiarDb)
        recargar()
    }

    override fun onPause() {
        super.onPause()
        db.quitarOyente(alCambiarDb)
    }

    override fun onDestroy() {
        super.onDestroy()
        lector.shutdown()
    }

    /** Vuelve a leer la misma cantidad de registros que se estaban mostrando (mínimo 10). */
    private fun recargar() {
        val cantidad = maxOf(PAGINA, registros.size)
        lector.execute {
            val lista = db.obtenerLecturas(cantidad)
            val cuenta = db.contarLecturas()
            val verificacion = db.verificarIntegridad()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                registros.clear()
                registros.addAll(lista)
                total = cuenta
                mostrarVerificacion(verificacion)
                pintar()
            }
        }
    }

    private fun cargarMas() {
        btnMostrarMas.isEnabled = false
        val desde = registros.size
        lector.execute {
            val mas = db.obtenerLecturas(PAGINA, desde)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                registros.addAll(mas)
                btnMostrarMas.isEnabled = true
                pintar()
            }
        }
    }

    private fun mostrarVerificacion(v: LecturasDb.Verificacion) {
        val (texto, color) = when {
            v.revisados == 0 ->
                "Base de datos vacía" to R.color.tinta_3

            v.alterados == 0 ->
                "${v.revisados} registros · cadena de integridad verificada" to R.color.ok

            else ->
                "⚠ ${v.alterados} de ${v.revisados} registros no coinciden con su hash" to R.color.error
        }
        tvResumen.text = texto
        vPuntoIntegridad.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, color))
    }

    private fun pintar() {
        // ----- tabla
        contenedorFilas.removeAllViews()
        registros.forEachIndexed { i, r ->
            if (i > 0) contenedorFilas.addView(divisor())
            contenedorFilas.addView(fila(i + 1, r))
        }

        tvVacio.visibility = if (registros.isEmpty()) View.VISIBLE else View.GONE
        tvMostrando.text = if (total == 0L) "" else "${registros.size} de $total"

        val faltan = total - registros.size
        btnMostrarMas.visibility = if (faltan > 0) View.VISIBLE else View.GONE
        btnMostrarMas.text = "Mostrar más (${minOf(faltan, PAGINA.toLong())})"

        // ----- gráficos (orden cronológico: el más antiguo a la izquierda)
        val cronologico = registros.asReversed()
        graficoTemperatura.mostrar(
            cronologico.map {
                GraficoLineaView.Punto(
                    formatoHora.format(Date(it.fechaMs)),
                    formatoFecha.format(Date(it.fechaMs)),
                    it.temperatura
                )
            },
            ContextCompat.getColor(this, R.color.temp),
            "°C"
        )
        graficoHumedad.mostrar(
            cronologico.map {
                GraficoLineaView.Punto(
                    formatoHora.format(Date(it.fechaMs)),
                    formatoFecha.format(Date(it.fechaMs)),
                    it.humedad
                )
            },
            ContextCompat.getColor(this, R.color.hum),
            "%"
        )
    }

    private fun fila(posicion: Int, r: LecturasDb.Registro): LinearLayout {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val h = dp(14)
            val v = dp(12)
            setPadding(h, v, h, v)
        }

        fila.addView(celda(posicion.toString(), 0.6f, Gravity.START, R.color.tinta_3))
        fila.addView(celda(formatoFecha.format(Date(r.fechaMs)), 1.5f, Gravity.START, R.color.tinta))
        fila.addView(celda(String.format(Locale.US, "%.1f", r.temperatura), 1f, Gravity.END, R.color.tinta))
        fila.addView(celda(String.format(Locale.US, "%.1f", r.humedad), 1f, Gravity.END, R.color.tinta))
        fila.addView(
            celda(
                if (r.verificada) "✓ chk" else "texto",
                0.8f,
                Gravity.END,
                if (r.verificada) R.color.ok else R.color.tinta_3
            ).apply { textSize = 11f }
        )

        fila.contentDescription =
            "Registro $posicion, ${formatoFecha.format(Date(r.fechaMs))}, " +
                "${r.temperatura} grados, ${r.humedad} por ciento de humedad"
        return fila
    }

    private fun celda(texto: String, peso: Float, gravedad: Int, color: Int) =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, peso)
            text = texto
            gravity = gravedad
            setTextAppearance(R.style.Texto_Celda)
            setTextColor(ContextCompat.getColor(this@HistorialActivity, color))
            maxLines = 1
        }

    private fun divisor() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
        }
        setBackgroundColor(ContextCompat.getColor(this@HistorialActivity, R.color.borde))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
