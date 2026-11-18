package com.example.test

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

enum class EstadoConexion { DESCONECTADO, CONECTANDO, CONECTADO, RECONECTANDO, FALLIDO }

/**
 * Enlace Bluetooth clásico (SPP) con el módulo HC-06.
 *
 * - Reconexión automática con espera exponencial (1, 2, 4, 8, 16, 30, 30, 30 s).
 * - Vigilancia del enlace: si no llegan datos en [TIMEOUT_SIN_DATOS_MS] se fuerza la reconexión.
 * - Lectura con límite de largo por línea (evita saturar memoria con datos basura).
 * - Cada línea pasa por [ValidadorTramas] antes de considerarse una lectura válida.
 */
@SuppressLint("MissingPermission")
object BluetoothManager {

    interface Oyente {
        fun onEstadoCambiado(estado: EstadoConexion, detalle: String) {}
        fun onMensaje(linea: String) {}
        fun onLectura(lectura: Lectura) {}
    }

    private val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val MAX_INTENTOS = 8
    private const val ESPERA_MAX_MS = 30_000L
    const val TIMEOUT_SIN_DATOS_MS = 30_000L
    private const val LARGO_MAX_LINEA = 160
    private const val MENSAJES_EN_MEMORIA = 6

    private val principal = Handler(Looper.getMainLooper())
    private val tareas = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "BT-Conexion").apply { isDaemon = true }
    }
    private val oyentes = CopyOnWriteArraySet<Oyente>()
    private val validador = ValidadorTramas()
    private val generacion = AtomicInteger()

    val estadisticas = EstadisticasIntegridad()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var dispositivo: BluetoothDevice? = null
    @Volatile private var desconexionManual = true
    @Volatile private var reconexionPendiente: ScheduledFuture<*>? = null
    @Volatile private var ultimoDatoMs = 0L
    @Volatile private var intentoActual = 0

    @Volatile var estado = EstadoConexion.DESCONECTADO
        private set
    @Volatile var detalleEstado = "Sin conexión"
        private set
    @Volatile var nombreDispositivo: String? = null
        private set
    @Volatile var ultimaLectura: Lectura? = null
        private set

    private val mensajes = ArrayDeque<String>()

    /** Se invoca en el hilo lector por cada lectura válida (lo usa el registro periódico). */
    @Volatile var onLecturaValida: ((Lectura) -> Unit)? = null

    /** Bitácora de eventos (conexión, reconexión, rechazos). */
    @Volatile var registroEventos: ((tipo: String, detalle: String) -> Unit)? = null

    val isConnected: Boolean
        get() = estado == EstadoConexion.CONECTADO

    val puedeReintentar: Boolean
        get() = dispositivo != null && estado != EstadoConexion.CONECTADO

    init {
        tareas.scheduleWithFixedDelay(::vigilarEnlace, 5, 5, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- oyentes

    fun agregarOyente(oyente: Oyente) {
        oyentes.add(oyente)
        val e = estado
        val d = detalleEstado
        principal.post { oyente.onEstadoCambiado(e, d) }
    }

    fun quitarOyente(oyente: Oyente) {
        oyentes.remove(oyente)
    }

    fun mensajesRecientes(): List<String> = synchronized(mensajes) { mensajes.toList() }

    // -------------------------------------------------------------- conexión

    fun conectar(device: BluetoothDevice) {
        desconexionManual = false
        dispositivo = device
        nombreDispositivo = try {
            device.name ?: "Dispositivo Bluetooth"
        } catch (e: SecurityException) {
            "Dispositivo Bluetooth"
        }
        intentoActual = 0
        cancelarReconexion()
        tareas.execute { abrirConexion(esReconexion = false) }
    }

    /** Reintento manual tras agotar los intentos automáticos. */
    fun reintentarAhora(): Boolean {
        if (dispositivo == null) return false
        desconexionManual = false
        intentoActual = 0
        cancelarReconexion()
        tareas.execute { abrirConexion(esReconexion = false) }
        return true
    }

    fun desconectar() {
        desconexionManual = true
        generacion.incrementAndGet()
        cancelarReconexion()
        intentoActual = 0
        cerrarSocket()
        evento("DESCONEXION_MANUAL", nombreDispositivo ?: "-")
        cambiarEstado(EstadoConexion.DESCONECTADO, "Desconectado por el usuario")
    }

    fun enviar(texto: String): Boolean {
        val activo = socket ?: return false
        if (estado != EstadoConexion.CONECTADO) return false

        return try {
            synchronized(activo) {
                activo.outputStream.write(texto.toByteArray(Charsets.UTF_8))
                activo.outputStream.flush()
            }
            true
        } catch (e: IOException) {
            perderConexionSiVigente(generacion.get(), "Fallo al enviar: ${e.message}")
            false
        }
    }

    private fun abrirConexion(esReconexion: Boolean) {
        val device = dispositivo ?: return
        if (desconexionManual) return

        cerrarSocket()

        if (esReconexion) {
            cambiarEstado(
                EstadoConexion.RECONECTANDO,
                "Reintento $intentoActual de $MAX_INTENTOS…"
            )
        } else {
            cambiarEstado(EstadoConexion.CONECTANDO, "Conectando con $nombreDispositivo…")
        }

        var nuevo: BluetoothSocket? = null
        try {
            nuevo = device.createRfcommSocketToServiceRecord(UUID_SPP)
            nuevo.connect()

            if (desconexionManual) {
                nuevo.close()
                return
            }

            socket = nuevo
            val gen = generacion.incrementAndGet()
            ultimoDatoMs = System.currentTimeMillis()
            validador.reiniciar()

            if (esReconexion) {
                estadisticas.reconexiones.incrementAndGet()
                evento("RECONEXION_OK", "$nombreDispositivo tras $intentoActual intento(s)")
            } else {
                evento("CONEXION_OK", nombreDispositivo ?: "-")
            }

            intentoActual = 0
            cambiarEstado(EstadoConexion.CONECTADO, "Enlazado con $nombreDispositivo")
            iniciarLectura(nuevo, gen)

        } catch (e: Exception) {
            try {
                nuevo?.close()
            } catch (_: IOException) {
            }

            if (esReconexion) {
                programarReconexion()
            } else {
                evento("CONEXION_FALLIDA", e.message ?: "Error desconocido")
                cambiarEstado(
                    EstadoConexion.FALLIDO,
                    "No se pudo conectar: ${e.message ?: "error desconocido"}"
                )
            }
        }
    }

    private fun programarReconexion() {
        if (desconexionManual) return

        intentoActual++

        if (intentoActual > MAX_INTENTOS) {
            evento("RECONEXION_AGOTADA", "$MAX_INTENTOS intentos sin éxito")
            cambiarEstado(
                EstadoConexion.FALLIDO,
                "Sin respuesta tras $MAX_INTENTOS intentos. Revisa el Arduino y pulsa Reintentar."
            )
            return
        }

        val espera = min(1000L shl (intentoActual - 1), ESPERA_MAX_MS)

        cambiarEstado(
            EstadoConexion.RECONECTANDO,
            "Reintento $intentoActual de $MAX_INTENTOS en ${espera / 1000} s"
        )

        reconexionPendiente = tareas.schedule(
            { abrirConexion(esReconexion = true) },
            espera,
            TimeUnit.MILLISECONDS
        )
    }

    /** Solo la primera señal de caída (lector, watchdog o envío) dispara la reconexión. */
    private fun perderConexionSiVigente(gen: Int, motivo: String) {
        if (!generacion.compareAndSet(gen, gen + 1)) return
        if (desconexionManual) return

        cerrarSocket()
        evento("CONEXION_PERDIDA", motivo)
        intentoActual = 0
        tareas.execute { programarReconexion() }
    }

    private fun vigilarEnlace() {
        if (estado != EstadoConexion.CONECTADO) return

        val silencio = System.currentTimeMillis() - ultimoDatoMs
        if (silencio > TIMEOUT_SIN_DATOS_MS) {
            perderConexionSiVigente(
                generacion.get(),
                "Sin datos durante ${silencio / 1000} s"
            )
        }
    }

    private fun cancelarReconexion() {
        reconexionPendiente?.cancel(false)
        reconexionPendiente = null
    }

    private fun cerrarSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    // ---------------------------------------------------------------- lectura

    private fun iniciarLectura(activo: BluetoothSocket, gen: Int) {
        Thread({
            try {
                val entrada = activo.inputStream
                val buffer = ByteArray(256)
                val linea = ByteArrayOutputStream()
                var descartando = false

                while (gen == generacion.get()) {
                    val leidos = entrada.read(buffer)
                    if (leidos < 0) throw IOException("El dispositivo cerró el canal")

                    ultimoDatoMs = System.currentTimeMillis()

                    for (i in 0 until leidos) {
                        val b = buffer[i].toInt()
                        when {
                            b == '\n'.code -> {
                                if (!descartando) {
                                    procesarLinea(linea.toString(Charsets.UTF_8.name()).trim())
                                }
                                linea.reset()
                                descartando = false
                            }

                            b == '\r'.code || descartando -> Unit

                            linea.size() >= LARGO_MAX_LINEA -> {
                                descartando = true
                                linea.reset()
                                registrarRechazo(
                                    ResultadoTrama.Rechazada(
                                        TipoRechazo.LONGITUD,
                                        "Línea de más de $LARGO_MAX_LINEA bytes descartada"
                                    )
                                )
                            }

                            else -> linea.write(b)
                        }
                    }
                }
            } catch (e: IOException) {
                perderConexionSiVigente(gen, e.message ?: "Error de lectura")
            }
        }, "BT-Lector").apply {
            isDaemon = true
            start()
        }
    }

    private fun procesarLinea(linea: String) {
        if (linea.isEmpty()) return

        synchronized(mensajes) {
            mensajes.addLast(linea)
            while (mensajes.size > MENSAJES_EN_MEMORIA) mensajes.removeFirst()
        }

        when (val resultado = validador.procesar(linea)) {
            is ResultadoTrama.Valida -> {
                estadisticas.validas.incrementAndGet()
                if (resultado.tramasPerdidas > 0) {
                    estadisticas.tramasPerdidas.addAndGet(resultado.tramasPerdidas)
                }
                val lectura = resultado.lectura
                ultimaLectura = lectura
                onLecturaValida?.invoke(lectura)
                principal.post { oyentes.forEach { it.onLectura(lectura) } }
            }

            is ResultadoTrama.Rechazada -> registrarRechazo(resultado)

            else -> Unit
        }

        principal.post { oyentes.forEach { it.onMensaje(linea) } }
    }

    private fun registrarRechazo(rechazo: ResultadoTrama.Rechazada) {
        estadisticas.rechazadas.incrementAndGet()
        if (rechazo.tipo == TipoRechazo.CHECKSUM) estadisticas.errorChecksum.incrementAndGet()
        estadisticas.ultimoRechazo = rechazo.motivo
        evento("TRAMA_RECHAZADA", "${rechazo.tipo}: ${rechazo.motivo}")
    }

    // ---------------------------------------------------------------- utilidades

    private fun cambiarEstado(nuevo: EstadoConexion, detalle: String) {
        estado = nuevo
        detalleEstado = detalle
        principal.post { oyentes.forEach { it.onEstadoCambiado(nuevo, detalle) } }
    }

    private fun evento(tipo: String, detalle: String) {
        try {
            registroEventos?.invoke(tipo, detalle)
        } catch (_: Exception) {
        }
    }
}
