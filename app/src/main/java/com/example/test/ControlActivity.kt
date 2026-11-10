
package com.example.test

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ControlActivity : AppCompatActivity() {

    private lateinit var tvEstadoConexion: TextView
    private lateinit var tvEstadoSistema: TextView
    private lateinit var tvTemperatura: TextView
    private lateinit var tvHumedad: TextView
    private lateinit var tvNotificaciones: TextView

    private lateinit var btnConectar: Button
    private lateinit var btnEnviarA: Button
    private lateinit var btnDesconectar: Button

    private val pedirPermiso = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->

        if (concedido) {
            abrirSelectorDispositivos()
        } else {
            mostrarMensaje("Permiso Bluetooth denegado")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        // Referencias a los elementos de la pantalla
        tvEstadoConexion = findViewById(R.id.tvEstadoConexion)
        tvEstadoSistema = findViewById(R.id.tvEstadoSistema)
        tvTemperatura = findViewById(R.id.tvTemperatura)
        tvHumedad = findViewById(R.id.tvHumedad)
        tvNotificaciones = findViewById(R.id.tvNotificaciones)

        btnConectar = findViewById(R.id.btnConectar)
        btnEnviarA = findViewById(R.id.btnEnviarA)
        btnDesconectar = findViewById(R.id.btnDesconectar)

        // Ajustar encabezado para respetar la barra de estado
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

        // Botón para volver
        findViewById<TextView>(R.id.btnVolverFlecha).setOnClickListener {
            finish()
        }

        // Estado inicial de los botones
        actualizarBotonEnviar(false)
        actualizarBotonDesconectar(false)

        // Botón para seleccionar dispositivo Bluetooth
        btnConectar.setOnClickListener {
            comprobarPermisoBluetooth()
        }

        // Botón para enviar comando A al Arduino
        btnEnviarA.setOnClickListener {

            val enviado = BluetoothManager.send("A")

            if (enviado) {
                tvNotificaciones.text = "Comando A enviado al Arduino"
            } else {
                tvNotificaciones.text = "No se pudo enviar el comando"
                mostrarMensaje("Se perdió la conexión Bluetooth")
                actualizarEstadoDesconectado()
            }
        }

        // Botón para desconectar Bluetooth
        btnDesconectar.setOnClickListener {
            desconectarBluetooth()
        }
    }

    private fun comprobarPermisoBluetooth() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            val permisoConcedido = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            if (permisoConcedido) {
                abrirSelectorDispositivos()
            } else {
                pedirPermiso.launch(
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            }

        } else {
            abrirSelectorDispositivos()
        }
    }

    private fun abrirSelectorDispositivos() {

        try {
            val bluetoothManager =
                getSystemService(AndroidBluetoothManager::class.java)

            val adaptador = bluetoothManager.adapter

            if (adaptador == null) {
                mostrarMensaje("Este celular no tiene Bluetooth")
                return
            }

            if (!adaptador.isEnabled) {
                mostrarMensaje("Activa el Bluetooth del celular")
                return
            }

            // Obtener dispositivos emparejados
            val dispositivos = adaptador.bondedDevices.toList()

            if (dispositivos.isEmpty()) {
                mostrarMensaje("No hay dispositivos emparejados")
                return
            }

            // Crear nombres para la ventana
            val nombres = dispositivos.map {
                it.name ?: "Dispositivo sin nombre"
            }

            // Mostrar ventana de selección
            AlertDialog.Builder(this)
                .setTitle("Seleccionar dispositivo Bluetooth")
                .setItems(nombres.toTypedArray()) { _, posicion ->

                    val dispositivoSeleccionado = dispositivos[posicion]
                    conectar(dispositivoSeleccionado)
                }
                .setNegativeButton("Cancelar", null)
                .show()

        } catch (e: SecurityException) {
            mostrarMensaje("No se pudo acceder al Bluetooth")
        }
    }

    private fun conectar(dispositivo: BluetoothDevice) {

        val nombre = try {
            dispositivo.name ?: "Dispositivo Bluetooth"
        } catch (e: SecurityException) {
            "Dispositivo Bluetooth"
        }

        tvEstadoConexion.text = "Conexión: conectando..."
        tvNotificaciones.text = "Intentando conectar con $nombre..."

        btnConectar.isEnabled = false
        btnDesconectar.isEnabled = false
        actualizarBotonEnviar(false)

        // La conexión es administrada por BluetoothManager.kt
        BluetoothManager.connect(dispositivo) { conectado, resultado ->

            runOnUiThread {

                if (conectado) {

                    tvEstadoConexion.text = "Conexión: conectado"
                    tvNotificaciones.text =
                        "Conexión establecida con $resultado"

                    btnConectar.text = "Conectado"
                    btnConectar.isEnabled = true

                    actualizarBotonEnviar(true)
                    actualizarBotonDesconectar(true)

                    // Registrar esta pantalla para recibir mensajes
                    BluetoothManager.setMessageListener { mensaje ->

                        runOnUiThread {
                            procesarMensajeArduino(mensaje)
                        }
                    }

                    // Comenzar a leer los mensajes
                    BluetoothManager.startReading()

                } else {

                    tvEstadoConexion.text = "Conexión: desconectado"
                    tvNotificaciones.text =
                        "Error al conectar con $nombre: $resultado"

                    btnConectar.text = "Conectar con HC-06"
                    btnConectar.isEnabled = true

                    actualizarBotonEnviar(false)
                    actualizarBotonDesconectar(false)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (BluetoothManager.isConnected) {

            btnConectar.text = "Conectado"
            btnConectar.isEnabled = true

            actualizarBotonEnviar(true)
            actualizarBotonDesconectar(true)

            tvEstadoConexion.text = "Conexión: conectado"

            BluetoothManager.setMessageListener { mensaje ->

                runOnUiThread {
                    procesarMensajeArduino(mensaje)
                }
            }

            BluetoothManager.startReading()

        } else {
            actualizarEstadoDesconectado()
        }
    }

    override fun onPause() {
        super.onPause()

        BluetoothManager.clearMessageListener()
    }

    private fun procesarMensajeArduino(mensaje: String) {

        // Buscar temperatura en el mensaje recibido
        val temperaturaRegex =
            Regex("(?:Temperatura|Temp):\\s*(-?\\d+(?:\\.\\d+)?)")

        // Buscar humedad en el mensaje recibido
        val humedadRegex =
            Regex("Humedad:\\s*(\\d+(?:\\.\\d+)?)")

        val temperatura = temperaturaRegex.find(mensaje)
            ?.groupValues?.get(1)

        val humedad = humedadRegex.find(mensaje)
            ?.groupValues?.get(1)

        // Actualizar valores
        if (temperatura != null) {
            tvTemperatura.text = "$temperatura °C"
        }

        if (humedad != null) {
            tvHumedad.text = "$humedad %"
        }

        // Mostrar estado del sistema
        tvEstadoSistema.text = when {
            mensaje.contains("ALERTA", ignoreCase = true) ->
                "Sistema: Alerta"

            mensaje.contains("-> OK", ignoreCase = true) ->
                "Sistema: Funcionando correctamente"

            else ->
                tvEstadoSistema.text
        }

        // Mostrar mensaje recibido
        tvNotificaciones.text = mensaje
    }

    private fun actualizarBotonEnviar(conectado: Boolean) {

        btnEnviarA.isEnabled = conectado

        val color = if (conectado) {
            "#CC1D00"
        } else {
            "#D99A91"
        }

        btnEnviarA.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(color))
    }

    private fun actualizarBotonDesconectar(conectado: Boolean) {

        btnDesconectar.isEnabled = conectado

        val color = if (conectado) {
            "#CC1D00"
        } else {
            "#D99A91"
        }

        btnDesconectar.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(color))
    }

    private fun actualizarEstadoDesconectado() {

        tvEstadoConexion.text = "Conexión: desconectado"
        tvEstadoSistema.text = "Esperando datos..."

        btnConectar.text = "Conectar con HC-06"
        btnConectar.isEnabled = true

        actualizarBotonEnviar(false)
        actualizarBotonDesconectar(false)
    }

    private fun desconectarBluetooth() {

        // Dejar de recibir mensajes en esta pantalla
        BluetoothManager.clearMessageListener()

        // Cerrar conexión Bluetooth
        BluetoothManager.disconnect()

        // Actualizar estado visual
        actualizarEstadoDesconectado()

        tvNotificaciones.text = "Dispositivo Bluetooth desconectado"

        mostrarMensaje("Bluetooth desconectado")
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(
            this,
            mensaje,
            Toast.LENGTH_LONG
        ).show()
    }
}
