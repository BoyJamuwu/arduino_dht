package com.example.test

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Almacenamiento temporal local (SQLite).
 *
 * - Tabla `lecturas`: un registro cada 5 minutos (promedio de la ventana).
 *   Cada fila guarda un hash SHA-256 encadenado con la fila anterior, de modo que
 *   cualquier modificación o borrado intermedio se detecta con [verificarIntegridad].
 * - Tabla `eventos`: bitácora de conexión, reconexión, rechazos y accesos.
 * - Retención: 30 días o 10.000 lecturas (lo que ocurra primero); 1.000 eventos.
 */
class LecturasDb private constructor(contexto: Context) :
    SQLiteOpenHelper(contexto, NOMBRE, null, VERSION) {

    data class Registro(
        val id: Long,
        val fechaMs: Long,
        val temperatura: Double,
        val humedad: Double,
        val verificada: Boolean
    )

    data class Verificacion(val revisados: Int, val alterados: Int)

    companion object {
        private const val NOMBRE = "monitor.db"
        private const val VERSION = 1

        const val RETENCION_DIAS = 30
        const val MAX_LECTURAS = 10_000
        const val MAX_EVENTOS = 1_000

        private const val HASH_INICIAL = "GENESIS"

        @Volatile
        private var instancia: LecturasDb? = null

        fun get(contexto: Context): LecturasDb =
            instancia ?: synchronized(this) {
                instancia ?: LecturasDb(contexto.applicationContext).also { instancia = it }
            }

        private fun fmt(valor: Double) = String.format(Locale.US, "%.2f", valor)

        fun calcularHash(
            previo: String,
            fechaMs: Long,
            temperatura: Double,
            humedad: Double,
            verificada: Boolean
        ): String {
            val texto = "$previo|$fechaMs|${fmt(temperatura)}|${fmt(humedad)}|${if (verificada) 1 else 0}"
            return MessageDigest.getInstance("SHA-256")
                .digest(texto.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    /** Todas las escrituras pasan por un único hilo: no hay escrituras concurrentes. */
    private val escritura = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DB-Escritura").apply { isDaemon = true }
    }
    private val principal = Handler(Looper.getMainLooper())
    private val oyentes = CopyOnWriteArraySet<() -> Unit>()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE lecturas (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                fecha_ms    INTEGER NOT NULL,
                temperatura REAL    NOT NULL CHECK (temperatura BETWEEN -40 AND 80),
                humedad     REAL    NOT NULL CHECK (humedad BETWEEN 0 AND 100),
                verificada  INTEGER NOT NULL DEFAULT 0 CHECK (verificada IN (0, 1)),
                hash        TEXT    NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_lecturas_fecha ON lecturas (fecha_ms)")

        db.execSQL(
            """
            CREATE TABLE eventos (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                fecha_ms INTEGER NOT NULL,
                tipo     TEXT    NOT NULL,
                detalle  TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_eventos_fecha ON eventos (fecha_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versión 1: sin migraciones todavía.
    }

    // ---------------------------------------------------------------- oyentes

    fun agregarOyente(oyente: () -> Unit) = oyentes.add(oyente)

    fun quitarOyente(oyente: () -> Unit) = oyentes.remove(oyente)

    // ---------------------------------------------------------------- escritura

    fun guardarLectura(lectura: Lectura) = escritura.execute {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previo = db.rawQuery(
                "SELECT hash FROM lecturas ORDER BY id DESC LIMIT 1", null
            ).use { if (it.moveToFirst()) it.getString(0) else HASH_INICIAL }

            val t = Math.round(lectura.temperatura * 10) / 10.0
            val h = Math.round(lectura.humedad * 10) / 10.0

            val valores = ContentValues().apply {
                put("fecha_ms", lectura.fechaMs)
                put("temperatura", t)
                put("humedad", h)
                put("verificada", if (lectura.verificada) 1 else 0)
                put("hash", calcularHash(previo, lectura.fechaMs, t, h, lectura.verificada))
            }
            db.insertOrThrow("lecturas", null, valores)
            db.setTransactionSuccessful()
        } catch (e: SQLiteException) {
            insertarEvento(db, "DB_ERROR", e.message ?: "Error al guardar lectura")
        } finally {
            db.endTransaction()
        }

        depurar(db)
        principal.post { oyentes.forEach { it() } }
    }

    fun registrarEvento(tipo: String, detalle: String) = escritura.execute {
        try {
            insertarEvento(writableDatabase, tipo, detalle)
        } catch (_: SQLiteException) {
        }
    }

    fun depurarAhora() = escritura.execute { depurar(writableDatabase) }

    private fun insertarEvento(db: SQLiteDatabase, tipo: String, detalle: String) {
        db.insert("eventos", null, ContentValues().apply {
            put("fecha_ms", System.currentTimeMillis())
            put("tipo", tipo.take(40))
            put("detalle", detalle.take(300))
        })
    }

    private fun depurar(db: SQLiteDatabase) {
        try {
            val limite = System.currentTimeMillis() - RETENCION_DIAS * 24L * 60 * 60 * 1000
            db.delete("lecturas", "fecha_ms < ?", arrayOf(limite.toString()))
            db.execSQL(
                "DELETE FROM lecturas WHERE id NOT IN " +
                    "(SELECT id FROM lecturas ORDER BY id DESC LIMIT $MAX_LECTURAS)"
            )
            db.execSQL(
                "DELETE FROM eventos WHERE id NOT IN " +
                    "(SELECT id FROM eventos ORDER BY id DESC LIMIT $MAX_EVENTOS)"
            )
        } catch (_: SQLiteException) {
        }
    }

    // ---------------------------------------------------------------- lectura

    /** Registros más recientes primero. Llamar fuera del hilo principal. */
    fun obtenerLecturas(limite: Int, desplazamiento: Int = 0): List<Registro> =
        readableDatabase.rawQuery(
            "SELECT id, fecha_ms, temperatura, humedad, verificada FROM lecturas " +
                "ORDER BY id DESC LIMIT ? OFFSET ?",
            arrayOf(limite.toString(), desplazamiento.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Registro(
                            id = c.getLong(0),
                            fechaMs = c.getLong(1),
                            temperatura = c.getDouble(2),
                            humedad = c.getDouble(3),
                            verificada = c.getInt(4) == 1
                        )
                    )
                }
            }
        }

    fun contarLecturas(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "lecturas")

    /**
     * Recalcula la cadena de hashes. La primera fila disponible se toma como
     * punto de partida (las anteriores pudieron eliminarse por la política de retención).
     */
    fun verificarIntegridad(): Verificacion {
        var revisados = 0
        var alterados = 0
        var previo: String? = null

        readableDatabase.rawQuery(
            "SELECT fecha_ms, temperatura, humedad, verificada, hash FROM lecturas ORDER BY id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val hash = c.getString(4)
                val anterior = previo
                if (anterior != null) {
                    val esperado = calcularHash(
                        anterior, c.getLong(0), c.getDouble(1), c.getDouble(2), c.getInt(3) == 1
                    )
                    if (esperado != hash) alterados++
                }
                revisados++
                previo = hash
            }
        }
        return Verificacion(revisados, alterados)
    }
}
