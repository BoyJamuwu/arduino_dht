
package com.example.test

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SensorActivity : AppCompatActivity() {

    private lateinit var tvTemperaturaActual: TextView
    private lateinit var tvHumedadActual: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensor)

        // Referencias a los valores de los sensores
        tvTemperaturaActual =
            findViewById(R.id.tvTemperaturaActual)

        tvHumedadActual =
            findViewById(R.id.tvHumedadActual)

        // Ajustar el encabezado para respetar la barra de estado
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

        // Volver a la pantalla anterior
        findViewById<TextView>(R.id.btnVolverFlecha).setOnClickListener {
            finish()
        }

        // Avanzar a la pantalla de control remoto
        findViewById<Button>(R.id.btnControl).setOnClickListener {
            val intent = Intent(this, ControlActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()

        // Mostrar los últimos valores guardados
        actualizarValores()

        // Recibir nuevas lecturas mientras esta pantalla esté activa
        BluetoothManager.setMessageListener {
            runOnUiThread {
                actualizarValores()
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // Dejar de actualizar esta pantalla al salir de ella
        BluetoothManager.clearMessageListener()
    }

    private fun actualizarValores() {

        BluetoothManager.lastTemperature?.let {
            tvTemperaturaActual.text = "$it °C"
        }

        BluetoothManager.lastHumidity?.let {
            tvHumedadActual.text = "$it %"
        }
    }
}
