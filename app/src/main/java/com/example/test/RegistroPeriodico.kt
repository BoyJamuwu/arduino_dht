package com.example.test

import kotlin.math.max

/**
 * Guarda en SQLite un registro cada [INTERVALO_MS] mientras haya lecturas válidas.
 *
 * El valor guardado es el PROMEDIO de todas las lecturas válidas de la ventana,
 * no una muestra suelta: así un valor ruidoso no queda como dato histórico.
 * El primer registro tras abrir la app se guarda de inmediato.
 */
object RegistroPeriodico {

    const val INTERVALO_MS = 5 * 60 * 1000L

    private val bloqueo = Any()
    private var db: LecturasDb? = null

    private var ultimoGuardadoMs = 0L
    private var ultimaLecturaMs = 0L
    private var sumaTemp = 0.0
    private var sumaHum = 0.0
    private var cantidad = 0
    private var todasVerificadas = true

    fun iniciar(baseDatos: LecturasDb) {
        db = baseDatos
        BluetoothManager.onLecturaValida = ::registrar
    }

    /** Milisegundos que faltan para el próximo guardado (0 si se guardará con la próxima lectura). */
    fun msHastaProximoGuardado(): Long = synchronized(bloqueo) {
        if (ultimoGuardadoMs == 0L) 0L
        else max(0L, ultimoGuardadoMs + INTERVALO_MS - System.currentTimeMillis())
    }

    private fun registrar(lectura: Lectura) {
        val aGuardar = synchronized(bloqueo) {
            // Si hubo un corte largo, la ventana anterior ya no representa el presente
            if (ultimaLecturaMs != 0L && lectura.fechaMs - ultimaLecturaMs > INTERVALO_MS) {
                reiniciarVentana()
            }
            ultimaLecturaMs = lectura.fechaMs

            sumaTemp += lectura.temperatura
            sumaHum += lectura.humedad
            cantidad++
            todasVerificadas = todasVerificadas && lectura.verificada

            if (ultimoGuardadoMs == 0L || lectura.fechaMs - ultimoGuardadoMs >= INTERVALO_MS) {
                val promedio = Lectura(
                    temperatura = sumaTemp / cantidad,
                    humedad = sumaHum / cantidad,
                    fechaMs = lectura.fechaMs,
                    verificada = todasVerificadas
                )
                ultimoGuardadoMs = lectura.fechaMs
                reiniciarVentana()
                promedio
            } else {
                null
            }
        }

        aGuardar?.let { db?.guardarLectura(it) }
    }

    private fun reiniciarVentana() {
        sumaTemp = 0.0
        sumaHum = 0.0
        cantidad = 0
        todasVerificadas = true
    }
}
