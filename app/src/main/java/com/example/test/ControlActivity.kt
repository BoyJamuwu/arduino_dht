package com.example.test

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale

class ControlActivity : BaseActivity() {

    private lateinit var vPuntoEstado: View
    private lateinit var tvEstadoConexion: TextView
    private lateinit var tvDispositivo: TextView
    private lateinit var tvDetalleConexion: TextView
    private lateinit var tvEstadoSistema: TextView
    private lateinit var tvTemperatura: TextView
    private lateinit var tvHumedad: TextView
    private lateinit var tvNotificaciones: TextView
    private lateinit var tvValidas: TextView
    private lateinit var tvRechazadas: TextView
    private lateinit var tvPerdidas: TextView
    private lateinit var tvReconexiones: TextView
    private lateinit var tvIntegridadDetalle: TextView

    private lateinit var btnConectar: MaterialButton
    private lateinit var btnReintentar: MaterialButton
    private lateinit var btnDesconectar: MaterialButton
    private lateinit var btnEnviarA: MaterialButton

    private val pedirPermiso = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) abrirSelectorDispositivos()
        else mostrarMensaje("Permiso Bluetooth denegado")
    }

    private val oyente = object : BluetoothManager.Oyente {
        override fun onEstadoCambiado(estado: EstadoConexion, detalle: String) =
            mostrarEstado(estado, detalle)

        override fun onMensaje(linea: String) {
            actualizarEstadoSistema(linea)
            actualizarTerminal()
            actualizarIntegridad()
        }

        override fun onLectura(lectura: Lectura) = mostrarLectura(lectura)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configurarPantalla(R.layout.activity_control, "04 · Control")

        vPuntoEstado = findViewById(R.id.vPuntoEstado)
        tvEstadoConexion = findViewById(R.id.tvEstadoConexion)
        tvDispositivo = findViewById(R.id.tvDispositivo)
        tvDetalleConexion = findViewById(R.id.tvDetalleConexion)
        tvEstadoSistema = findViewById(R.id.tvEstadoSistema)
        tvTemperatura = findViewById(R.id.tvTemperatura)
        tvHumedad = findViewById(R.id.tvHumedad)
        tvNotificaciones = findViewById(R.id.tvNotificaciones)
        tvValidas = findViewById(R.id.tvValidas)
        tvRechazadas = findViewById(R.id.tvRechazadas)
        tvPerdidas = findViewById(R.id.tvPerdidas)
        tvReconexiones = findViewById(R.id.tvReconexiones)
        tvIntegridadDetalle = findViewById(R.id.tvIntegridadDetalle)

        btnConectar = findViewById(R.id.btnConectar)
        btnReintentar = findViewById(R.id.btnReintentar)
        btnDesconectar = findViewById(R.id.btnDesconectar)
        btnEnviarA = findViewById(R.id.btnEnviarA)

        btnConectar.setOnClickListener { comprobarPermisoBluetooth() }

        btnReintentar.setOnClickListener {
            if (!BluetoothManager.reintentarAhora()) {
                mostrarMensaje("Primero selecciona un dispositivo")
            }
        }

        btnDesconectar.setOnClickListener {
            BluetoothManager.desconectar()
            mostrarMensaje("Bluetooth desconectado")
        }

        btnEnviarA.setOnClickListener {
            if (BluetoothManager.enviar("A")) {
                mostrarMensaje("Comando A enviado al Arduino")
            } else {
                mostrarMensaje("No se pudo enviar: la app intentará reconectar")
            }
        }

        findViewById<View>(R.id.btnHistorial).setOnClickListener {
            startActivity(Intent(this, HistorialActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        BluetoothManager.ultimaLectura?.let { mostrarLectura(it) }
        actualizarTerminal()
        actualizarIntegridad()
        BluetoothManager.agregarOyente(oyente)
    }

    override fun onPause() {
        super.onPause()
        BluetoothManager.quitarOyente(oyente)
    }

    // ---------------------------------------------------------------- Bluetooth

    private fun comprobarPermisoBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pedirPermiso.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            abrirSelectorDispositivos()
        }
    }

    private fun abrirSelectorDispositivos() {
        try {
            val adaptador = getSystemService(AndroidBluetoothManager::class.java)?.adapter

            if (adaptador == null) {
                mostrarMensaje("Este celular no tiene Bluetooth")
                return
            }
            if (!adaptador.isEnabled) {
                mostrarMensaje("Activa el Bluetooth del celular")
                return
            }

            // Solo se permiten dispositivos previamente emparejados (con PIN)
            val dispositivos = adaptador.bondedDevices.toList()
            if (dispositivos.isEmpty()) {
                mostrarMensaje("No hay dispositivos emparejados")
                return
            }

            val nombres = dispositivos.map { it.name ?: "Dispositivo sin nombre" }

            AlertDialog.Builder(this)
                .setTitle("Seleccionar dispositivo")
                .setItems(nombres.toTypedArray()) { _, posicion ->
                    conectar(dispositivos[posicion])
                }
                .setNegativeButton("Cancelar", null)
                .show()

        } catch (e: SecurityException) {
            mostrarMensaje("No se pudo acceder al Bluetooth")
        }
    }

    private fun conectar(dispositivo: BluetoothDevice) {
        BluetoothManager.conectar(dispositivo)
    }

    // ---------------------------------------------------------------- interfaz

    private fun mostrarEstado(estado: EstadoConexion, detalle: String) {
        val (texto, color) = when (estado) {
            EstadoConexion.DESCONECTADO -> "Desconectado" to R.color.tinta_3
            EstadoConexion.CONECTANDO -> "Conectando…" to R.color.alerta
            EstadoConexion.CONECTADO -> "Conectado" to R.color.ok
            EstadoConexion.RECONECTANDO -> "Reconectando…" to R.color.alerta
            EstadoConexion.FALLIDO -> "Sin conexión" to R.color.error
        }

        tvEstadoConexion.text = texto
        vPuntoEstado.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, color))
        tvDetalleConexion.text = detalle
        tvDispositivo.text = BluetoothManager.nombreDispositivo ?: "HC-06"

        val ocupado = estado == EstadoConexion.CONECTANDO
        btnConectar.isEnabled = !ocupado
        btnConectar.text = if (estado == EstadoConexion.CONECTADO) "Cambiar dispositivo" else "Conectar dispositivo"

        btnReintentar.isEnabled = BluetoothManager.puedeReintentar && !ocupado
        btnReintentar.text = if (estado == EstadoConexion.RECONECTANDO) "Reintentar ya" else "Reintentar"

        btnDesconectar.isEnabled = estado != EstadoConexion.DESCONECTADO
        btnEnviarA.isEnabled = estado == EstadoConexion.CONECTADO

        if (estado != EstadoConexion.CONECTADO) {
            tvEstadoSistema.text = "Esperando datos"
            pintarChipSistema(R.color.superficie_alt, R.color.tinta_2)
        }
    }

    private fun mostrarLectura(lectura: Lectura) {
        tvTemperatura.text = String.format(Locale.US, "%.1f", lectura.temperatura)
        tvHumedad.text = String.format(Locale.US, "%.1f", lectura.humedad)
    }

    private fun actualizarEstadoSistema(mensaje: String) {
        when {
            mensaje.contains("ALERTA", ignoreCase = true) -> {
                tvEstadoSistema.text = "⚠ Alerta"
                pintarChipSistema(R.color.alerta_suave, R.color.alerta)
            }

            mensaje.contains("-> OK", ignoreCase = true) -> {
                tvEstadoSistema.text = "✓ Normal"
                pintarChipSistema(R.color.ok_suave, R.color.ok)
            }
        }
    }

    private fun pintarChipSistema(fondo: Int, texto: Int) {
        tvEstadoSistema.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, fondo))
        tvEstadoSistema.setTextColor(ContextCompat.getColor(this, texto))
    }

    private fun actualizarTerminal() {
        val lineas = BluetoothManager.mensajesRecientes()
        tvNotificaciones.text = if (lineas.isEmpty()) {
            "› sin mensajes todavía"
        } else {
            lineas.joinToString("\n") { "› $it" }
        }
    }

    private fun actualizarIntegridad() {
        val e = BluetoothManager.estadisticas
        tvValidas.text = e.validas.get().toString()
        tvRechazadas.text = e.rechazadas.get().toString()
        tvPerdidas.text = e.tramasPerdidas.get().toString()
        tvReconexiones.text = e.reconexiones.get().toString()

        val proximo = RegistroPeriodico.msHastaProximoGuardado()
        val textoProximo = if (proximo == 0L) {
            "Próximo registro en la base de datos: con la siguiente lectura válida."
        } else {
            val min = proximo / 60_000
            val seg = (proximo / 1000) % 60
            "Próximo registro en la base de datos: en ${min} min ${seg} s."
        }

        tvIntegridadDetalle.text = e.ultimoRechazo
            ?.let { "$textoProximo\nÚltimo rechazo: $it" }
            ?: textoProximo
    }
}
