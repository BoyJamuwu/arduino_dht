package com.example.test

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Base común de todas las pantallas:
 * - Dibujo de borde a borde con íconos oscuros en las barras del sistema.
 * - Márgenes seguros: la vista raíz recibe el alto de la barra de estado, de la
 *   barra de navegación, del recorte de cámara y del teclado.
 * - Encabezado con botón volver y etiqueta de paso.
 * - Control de sesión por inactividad (ISO/IEC 27400).
 */
abstract class BaseActivity : AppCompatActivity() {

    /** Las pantallas posteriores al login exigen una sesión vigente. */
    protected open val requiereSesion: Boolean = true

    protected fun configurarPantalla(layout: Int, etiquetaPaso: String? = null) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContentView(layout)

        aplicarMargenesSeguros(findViewById(R.id.raiz))

        findViewById<View?>(R.id.btnVolver)?.setOnClickListener { finish() }

        findViewById<TextView?>(R.id.tvPaso)?.let { chip ->
            if (etiquetaPaso == null) chip.visibility = View.GONE else chip.text = etiquetaPaso
        }
    }

    private fun aplicarMargenesSeguros(raiz: View) {
        val izquierda = raiz.paddingLeft
        val arriba = raiz.paddingTop
        val derecha = raiz.paddingRight
        val abajo = raiz.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(raiz) { vista, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime())

            vista.setPadding(
                izquierda + barras.left,
                arriba + barras.top,
                derecha + barras.right,
                abajo + max(barras.bottom, teclado.bottom)
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()

        if (requiereSesion && !Sesion.vigente()) {
            Sesion.cerrar()
            Toast.makeText(this, "Sesión expirada. Vuelve a ingresar.", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        } else {
            Sesion.renovar()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        Sesion.renovar()
    }

    protected fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }
}
