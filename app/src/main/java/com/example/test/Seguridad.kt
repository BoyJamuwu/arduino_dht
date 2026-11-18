package com.example.test

import android.content.Context
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.ceil
import kotlin.math.min

/**
 * Controles de acceso alineados con ISO/IEC 27400 (IoT – seguridad y privacidad):
 *
 * - La contraseña NO está en texto plano: solo se guarda su derivación PBKDF2-SHA256 con sal.
 * - Comparación en tiempo constante (evita ataques por tiempo de respuesta).
 * - Bloqueo progresivo tras [MAX_FALLOS] intentos fallidos (1, 2, 4… hasta 15 minutos).
 * - Cierre de sesión por inactividad ([Sesion]).
 * - Cada intento queda registrado en la bitácora de eventos.
 */
object Autenticacion {

    sealed class Resultado {
        object Correcto : Resultado()
        data class Incorrecto(val intentosRestantes: Int) : Resultado()
        data class Bloqueado(val segundos: Long) : Resultado()
    }

    const val MAX_FALLOS = 5
    private const val BLOQUEO_BASE_MS = 60_000L
    private const val BLOQUEO_MAX_MS = 15 * 60_000L
    private const val ITERACIONES = 20_000

    private const val USUARIO = "admin"
    private val SAL = hex("1734223c67be870f41e1e1377e364de5")
    private val HASH = hex("c738c83744d70b5d9120b38c3241568ef215e3fc96d1e6ee7c2780e716822b23")

    private const val PREFS = "seguridad"
    private const val K_FALLOS = "fallos"
    private const val K_BLOQUEOS = "bloqueos"
    private const val K_BLOQUEADO_HASTA = "bloqueado_hasta"

    /** Operación lenta a propósito (PBKDF2): llamar fuera del hilo principal. */
    fun validar(contexto: Context, usuario: String, password: CharArray): Resultado {
        val prefs = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val db = LecturasDb.get(contexto)
        val ahora = System.currentTimeMillis()

        val bloqueadoHasta = prefs.getLong(K_BLOQUEADO_HASTA, 0L)
        if (ahora < bloqueadoHasta) {
            password.fill('\u0000')
            return Resultado.Bloqueado(ceil((bloqueadoHasta - ahora) / 1000.0).toLong())
        }

        val derivada = derivar(password)
        password.fill('\u0000')

        val usuarioOk = MessageDigest.isEqual(
            usuario.toByteArray(Charsets.UTF_8),
            USUARIO.toByteArray(Charsets.UTF_8)
        )
        val claveOk = MessageDigest.isEqual(derivada, HASH)

        if (usuarioOk && claveOk) {
            prefs.edit().putInt(K_FALLOS, 0).putInt(K_BLOQUEOS, 0).apply()
            db.registrarEvento("LOGIN_OK", "Usuario autenticado")
            return Resultado.Correcto
        }

        val fallos = prefs.getInt(K_FALLOS, 0) + 1
        db.registrarEvento("LOGIN_FALLIDO", "Intento $fallos de $MAX_FALLOS")

        if (fallos >= MAX_FALLOS) {
            val bloqueos = prefs.getInt(K_BLOQUEOS, 0) + 1
            val duracion = min(BLOQUEO_BASE_MS shl (bloqueos - 1).coerceAtMost(10), BLOQUEO_MAX_MS)
            prefs.edit()
                .putInt(K_FALLOS, 0)
                .putInt(K_BLOQUEOS, bloqueos)
                .putLong(K_BLOQUEADO_HASTA, ahora + duracion)
                .apply()
            db.registrarEvento("LOGIN_BLOQUEADO", "Bloqueo de ${duracion / 1000} s")
            return Resultado.Bloqueado(duracion / 1000)
        }

        prefs.edit().putInt(K_FALLOS, fallos).apply()
        return Resultado.Incorrecto(MAX_FALLOS - fallos)
    }

    private fun derivar(password: CharArray): ByteArray {
        val spec = PBEKeySpec(password, SAL, ITERACIONES, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}

/** Sesión en memoria con expiración por inactividad. */
object Sesion {

    const val INACTIVIDAD_MAX_MS = 10 * 60_000L

    @Volatile
    private var ultimaActividadMs = 0L

    fun iniciar() {
        ultimaActividadMs = System.currentTimeMillis()
    }

    fun cerrar() {
        ultimaActividadMs = 0L
    }

    fun vigente(): Boolean =
        ultimaActividadMs != 0L &&
            System.currentTimeMillis() - ultimaActividadMs < INACTIVIDAD_MAX_MS

    fun renovar() {
        if (vigente()) ultimaActividadMs = System.currentTimeMillis()
    }
}
