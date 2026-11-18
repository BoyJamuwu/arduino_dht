package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SensorActivity : BaseActivity() {

    private lateinit var tvTemperatura: TextView
    private lateinit var tvHumedad: TextView
    private lateinit var tvActualizado: TextView

    private val formatoHora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val oyente = object : BluetoothManager.Oyente {
        override fun onLectura(lectura: Lectura) = mostrar(lectura)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configurarPantalla(R.layout.activity_sensor, "03 · Sensor")

        tvTemperatura = findViewById(R.id.tvTemperatura)
        tvHumedad = findViewById(R.id.tvHumedad)
        tvActualizado = findViewById(R.id.tvActualizado)

        findViewById<View>(R.id.btnControl).setOnClickListener {
            startActivity(Intent(this, ControlActivity::class.java))
        }
        findViewById<View>(R.id.btnHistorial).setOnClickListener {
            startActivity(Intent(this, HistorialActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        BluetoothManager.ultimaLectura?.let { mostrar(it) }
        BluetoothManager.agregarOyente(oyente)
    }

    override fun onPause() {
        super.onPause()
        BluetoothManager.quitarOyente(oyente)
    }

    private fun mostrar(lectura: Lectura) {
        tvTemperatura.text = String.format(Locale.US, "%.1f", lectura.temperatura)
        tvHumedad.text = String.format(Locale.US, "%.1f", lectura.humedad)
        tvActualizado.text = "Act. ${formatoHora.format(Date(lectura.fechaMs))}"
    }
}
