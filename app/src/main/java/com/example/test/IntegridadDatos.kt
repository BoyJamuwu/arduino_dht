package com.example.test

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Integridad de los datos recibidos desde el Arduino.
 *
 * Se aceptan dos formatos:
 *
 * 1. Trama verificada (recomendada):   $T=23.5,H=45.0,N=12*5A
 *    - T = temperatura, H = humedad, N = número de secuencia (opcional)
 *    - *5A = checksum XOR (hexadecimal) de todos los caracteres entre '$' y '*'
 *
 * 2. Texto libre (formato original):   Temperatura: 23.5  Humedad: 45
 *    Puede venir en una sola línea o en dos líneas seguidas.
 *
 * Toda lectura pasa por: checksum (si existe) → rango físico → filtro de saltos bruscos.
 */

data class Lectura(
    val temperatura: Double,
    val humedad: Double,
    val fechaMs: Long,
    /** true si la lectura llegó en una trama con checksum correcto. */
    val verificada: Boolean
)

enum class TipoRechazo { CHECKSUM, FORMATO, RANGO, SALTO, LONGITUD }

sealed class ResultadoTrama {
    data class Valida(val lectura: Lectura, val tramasPerdidas: Int) : ResultadoTrama()
    data class Rechazada(val tipo: TipoRechazo, val motivo: String) : ResultadoTrama()

    /** Llegó solo temperatura o solo humedad; se espera la otra mitad. */
    object Parcial : ResultadoTrama()

    /** Mensaje informativo sin valores (por ejemplo "ALERTA" o "-> OK"). */
    object SinDatos : ResultadoTrama()
}

class EstadisticasIntegridad {
    val validas = AtomicInteger()
    val rechazadas = AtomicInteger()
    val errorChecksum = AtomicInteger()
    val tramasPerdidas = AtomicInteger()
    val reconexiones = AtomicInteger()

    @Volatile
    var ultimoRechazo: String? = null
}

class ValidadorTramas {

    companion object {
        // Límites físicos aceptados (el DHT11 mide 0–50 °C y 20–90 %HR; se deja holgura)
        const val TEMP_MIN = -20.0
        const val TEMP_MAX = 60.0
        const val HUM_MIN = 0.0
        const val HUM_MAX = 100.0

        // Un DHT11 no puede variar tanto en pocos segundos: se considera ruido
        const val SALTO_MAX_TEMP = 8.0
        const val SALTO_MAX_HUM = 25.0
        const val VENTANA_SALTO_MS = 15_000L

        // Tiempo máximo entre "Temperatura" y "Humedad" cuando llegan en líneas separadas
        const val VENTANA_PARCIAL_MS = 3_000L

        // Mientras lleguen tramas con checksum, se ignoran los valores en texto libre
        const val PRIORIDAD_VERIFICADA_MS = 15_000L

        private val REGEX_TEMP =
            Regex("""(?:Temperatura|Temp):\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        private val REGEX_HUM =
            Regex("""Humedad:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        /** Checksum XOR estilo NMEA. Se expone para pruebas y para documentar el formato. */
        fun checksum(cuerpo: String): Int = cuerpo.fold(0) { acc, c -> acc xor (c.code and 0xFF) }
    }

    private var ultimaValida: Lectura? = null
    private var ultimaSecuencia: Int? = null
    private var ultimaVerificadaMs = Long.MIN_VALUE / 2

    private var tempPendiente: Double? = null
    private var tempPendienteMs = 0L
    private var humPendiente: Double? = null
    private var humPendienteMs = 0L

    @Synchronized
    fun reiniciar() {
        tempPendiente = null
        humPendiente = null
        ultimaSecuencia = null
    }

    @Synchronized
    fun procesar(linea: String, ahora: Long = System.currentTimeMillis()): ResultadoTrama {
        return if (linea.startsWith("$")) {
            procesarTramaVerificada(linea, ahora)
        } else {
            procesarTextoLibre(linea, ahora)
        }
    }

    private fun procesarTramaVerificada(linea: String, ahora: Long): ResultadoTrama {
        val asterisco = linea.lastIndexOf('*')

        if (asterisco < 1 || linea.length != asterisco + 3) {
            return ResultadoTrama.Rechazada(TipoRechazo.FORMATO, "Trama mal formada")
        }

        val cuerpo = linea.substring(1, asterisco)
        val recibido = linea.substring(asterisco + 1).toIntOrNull(16)
            ?: return ResultadoTrama.Rechazada(TipoRechazo.FORMATO, "Checksum ilegible")

        val calculado = checksum(cuerpo)
        if (recibido != calculado) {
            return ResultadoTrama.Rechazada(
                TipoRechazo.CHECKSUM,
                "Checksum incorrecto (recibido %02X, esperado %02X)".format(recibido, calculado)
            )
        }

        val campos = cuerpo.split(',')
            .mapNotNull { par ->
                val partes = par.split('=', limit = 2)
                if (partes.size == 2) partes[0].trim().uppercase() to partes[1].trim() else null
            }
            .toMap()

        val t = campos["T"]?.toDoubleOrNull()
        val h = campos["H"]?.toDoubleOrNull()
        val n = campos["N"]?.toIntOrNull()

        if (t == null || h == null) {
            return ResultadoTrama.Rechazada(TipoRechazo.FORMATO, "Faltan campos T/H")
        }

        // Detección de tramas perdidas por número de secuencia
        var perdidas = 0
        val anterior = ultimaSecuencia
        if (n != null) {
            if (anterior != null && n > anterior + 1) perdidas = n - anterior - 1
            ultimaSecuencia = n
        }

        ultimaVerificadaMs = ahora

        return when (val r = validar(t, h, verificada = true, ahora)) {
            is ResultadoTrama.Valida -> r.copy(tramasPerdidas = perdidas)
            else -> r
        }
    }

    private fun procesarTextoLibre(linea: String, ahora: Long): ResultadoTrama {
        // Si el Arduino ya envía tramas verificadas, el texto libre es solo informativo
        // (evita contar dos veces la misma medición con menor garantía de integridad).
        if (ahora - ultimaVerificadaMs < PRIORIDAD_VERIFICADA_MS) return ResultadoTrama.SinDatos

        val t = REGEX_TEMP.find(linea)?.groupValues?.get(1)?.toDoubleOrNull()
        val h = REGEX_HUM.find(linea)?.groupValues?.get(1)?.toDoubleOrNull()

        if (t == null && h == null) return ResultadoTrama.SinDatos

        if (t != null) {
            tempPendiente = t
            tempPendienteMs = ahora
        }
        if (h != null) {
            humPendiente = h
            humPendienteMs = ahora
        }

        val tp = tempPendiente
        val hp = humPendiente

        if (tp != null && hp != null && abs(tempPendienteMs - humPendienteMs) <= VENTANA_PARCIAL_MS) {
            tempPendiente = null
            humPendiente = null
            return validar(tp, hp, verificada = false, ahora)
        }

        return ResultadoTrama.Parcial
    }

    private fun validar(t: Double, h: Double, verificada: Boolean, ahora: Long): ResultadoTrama {
        if (t.isNaN() || h.isNaN()) {
            return ResultadoTrama.Rechazada(TipoRechazo.RANGO, "Valor no numérico")
        }
        if (t !in TEMP_MIN..TEMP_MAX) {
            return ResultadoTrama.Rechazada(TipoRechazo.RANGO, "Temperatura fuera de rango: $t °C")
        }
        if (h !in HUM_MIN..HUM_MAX) {
            return ResultadoTrama.Rechazada(TipoRechazo.RANGO, "Humedad fuera de rango: $h %")
        }

        val previa = ultimaValida
        if (previa != null && ahora - previa.fechaMs <= VENTANA_SALTO_MS) {
            if (abs(t - previa.temperatura) > SALTO_MAX_TEMP) {
                return ResultadoTrama.Rechazada(
                    TipoRechazo.SALTO,
                    "Salto brusco de temperatura (${previa.temperatura} → $t)"
                )
            }
            if (abs(h - previa.humedad) > SALTO_MAX_HUM) {
                return ResultadoTrama.Rechazada(
                    TipoRechazo.SALTO,
                    "Salto brusco de humedad (${previa.humedad} → $h)"
                )
            }
        }

        val lectura = Lectura(t, h, ahora, verificada)
        ultimaValida = lectura
        return ResultadoTrama.Valida(lectura, 0)
    }
}
