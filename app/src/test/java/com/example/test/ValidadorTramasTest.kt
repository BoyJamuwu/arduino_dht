package com.example.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidadorTramasTest {

    private fun trama(cuerpo: String) =
        "$" + cuerpo + "*" + "%02X".format(ValidadorTramas.checksum(cuerpo))

    @Test
    fun tramaConChecksumCorrectoEsValida() {
        val r = ValidadorTramas().procesar(trama("T=23.5,H=45.0,N=1"), 1_000)
        assertTrue(r is ResultadoTrama.Valida)
        r as ResultadoTrama.Valida
        assertEquals(23.5, r.lectura.temperatura, 0.0)
        assertEquals(45.0, r.lectura.humedad, 0.0)
        assertTrue(r.lectura.verificada)
    }

    @Test
    fun checksumIncorrectoSeRechaza() {
        val r = ValidadorTramas().procesar("\$T=23.5,H=45.0,N=1*00", 1_000)
        assertTrue(r is ResultadoTrama.Rechazada)
        assertEquals(TipoRechazo.CHECKSUM, (r as ResultadoTrama.Rechazada).tipo)
    }

    @Test
    fun detectaTramasPerdidasPorSecuencia() {
        val v = ValidadorTramas()
        v.procesar(trama("T=23.5,H=45.0,N=1"), 1_000)
        val r = v.procesar(trama("T=23.6,H=45.0,N=4"), 3_000)
        assertEquals(2, (r as ResultadoTrama.Valida).tramasPerdidas)
    }

    @Test
    fun formatoTextoEnUnaLinea() {
        val r = ValidadorTramas().procesar("Temperatura: 24.0 C  Humedad: 51.0 %", 1_000)
        assertTrue(r is ResultadoTrama.Valida)
        assertTrue(!(r as ResultadoTrama.Valida).lectura.verificada)
    }

    @Test
    fun formatoTextoEnDosLineas() {
        val v = ValidadorTramas()
        assertTrue(v.procesar("Temperatura: 24.0", 1_000) is ResultadoTrama.Parcial)
        assertTrue(v.procesar("Humedad: 51", 1_500) is ResultadoTrama.Valida)
    }

    @Test
    fun valorFueraDeRangoSeRechaza() {
        val r = ValidadorTramas().procesar("Temperatura: 95.0 Humedad: 40", 1_000)
        assertEquals(TipoRechazo.RANGO, (r as ResultadoTrama.Rechazada).tipo)
    }

    @Test
    fun saltoBruscoSeRechaza() {
        val v = ValidadorTramas()
        v.procesar("Temperatura: 24.0 Humedad: 50", 1_000)
        val r = v.procesar("Temperatura: 40.0 Humedad: 50", 3_000)
        assertEquals(TipoRechazo.SALTO, (r as ResultadoTrama.Rechazada).tipo)
    }

    @Test
    fun conTramasVerificadasElTextoLibreNoDuplicaLecturas() {
        val v = ValidadorTramas()
        v.procesar(trama("T=25.0,H=51.0,N=1"), 1_000)
        val r = v.procesar("ALERTA HUMEDAD | Temp: 25.0 C | Humedad: 51.0 %", 1_100)
        assertTrue(r is ResultadoTrama.SinDatos)
    }

    @Test
    fun formatoRealDelArduino() {
        val r = ValidadorTramas().procesar("ALERTA HUMEDAD | Temp: 25.0 C | Humedad: 51.0 %", 1_000)
        assertEquals(51.0, (r as ResultadoTrama.Valida).lectura.humedad, 0.0)
    }

    @Test
    fun mensajeSinValoresNoEsLectura() {
        assertTrue(ValidadorTramas().procesar("Sistema -> OK", 1_000) is ResultadoTrama.SinDatos)
    }
}
