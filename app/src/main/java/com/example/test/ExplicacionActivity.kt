package com.example.test

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ExplicacionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_explicacion)

        // Ajustar el encabezado rojo a la barra de estado
        val header = findViewById<LinearLayout>(R.id.headerSuperior)
        val altoBase = (64 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->

            val altoBarraEstado =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

            view.setPadding(
                view.paddingLeft,
                altoBarraEstado,
                view.paddingRight,
                view.paddingBottom
            )

            view.layoutParams = view.layoutParams.apply {
                height = altoBase + altoBarraEstado
            }

            insets
        }

        ViewCompat.requestApplyInsets(header)

        // Flecha para volver al login
        val btnVolverFlecha =
            findViewById<TextView>(R.id.btnVolverFlecha)

        btnVolverFlecha.setOnClickListener {
            finish()
        }

        // Botón para avanzar a la información del sensor
        val btnSiguiente =
            findViewById<Button>(R.id.btnSiguiente)

        btnSiguiente.setOnClickListener {
            val intent = Intent(this, SensorActivity::class.java)
            startActivity(intent)
        }
    }
}
