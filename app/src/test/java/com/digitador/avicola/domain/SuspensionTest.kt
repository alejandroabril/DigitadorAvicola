package com.digitador.avicola.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * Una jaula suspendida se sigue digitando pero no entra en ningún indicador. Lo que se
 * fija aquí es que suspenderla dé EXACTAMENTE lo mismo que si no estuviera en el lote.
 */
class SuspensionTest {

    private val tol = 1e-9
    private val semanas = listOf(Semana(1, refsActivas = listOf("BR1")))

    private fun jaula(id: String, aves: Int, susp: Boolean = false) =
        Parcela(id, "G1-K1", inicio = aves, pesoInicio = aves * 40.0, suspendida = susp)

    private fun datos(vararg pesos: Pair<String, Double>) = pesos.associate { (id, peso) ->
        id to mapOf(
            1 to DatoParcela(
                1, id, mort = List(7) { 1 }, peso = peso,
                refs = mapOf("BR1" to RefAlimento("BR1", ingreso = 20.0, saldoFin = 0.0))
            )
        )
    }

    private val d = datos("P1" to 200.0, "P2" to 200.0, "P3" to 900.0)

    @Test
    fun `suspender una jaula da lo mismo que si no estuviera en el lote`() {
        val conSuspendida = listOf(jaula("P1", 30), jaula("P2", 30), jaula("P3", 30, susp = true))
        val sinElla = listOf(jaula("P1", 30), jaula("P2", 30))

        val a = Calculadora.computeMetricasDeParcelas(conSuspendida, 1, semanas[0], semanas, d)!!
        val b = Calculadora.computeMetricasDeParcelas(sinElla, 1, semanas[0], semanas, d)!!
        assertEquals(b, a)
    }

    @Test
    fun `la jaula suspendida no arrastra el peso promedio`() {
        val todas = listOf(jaula("P1", 30), jaula("P2", 30), jaula("P3", 30))
        val conSusp = listOf(jaula("P1", 30), jaula("P2", 30), jaula("P3", 30, susp = true))

        // Con las tres, el peso de 900 g de P3 tira del promedio hacia arriba.
        assertEquals(
            (200.0 * 29 + 200.0 * 29 + 900.0 * 29) / 87,
            Calculadora.computeMetricasDeParcelas(todas, 1, semanas[0], semanas, d)!!.promPeso, tol
        )
        // Suspendida, el promedio es solo el de las otras dos.
        assertEquals(200.0, Calculadora.computeMetricasDeParcelas(conSusp, 1, semanas[0], semanas, d)!!.promPeso, tol)
    }

    /** La mortalidad de una jaula suspendida tampoco cuenta, ni arriba ni abajo. */
    @Test
    fun `la jaula suspendida sale del denominador de la mortalidad`() {
        val conSusp = listOf(jaula("P1", 30), jaula("P2", 30), jaula("P3", 30, susp = true))
        val m = Calculadora.computeMetricasDeParcelas(conSusp, 1, semanas[0], semanas, d)!!
        // 7 muertas por jaula, 2 jaulas contadas, 60 aves iniciales.
        assertEquals(14.0 / 60.0, m.mortAcumPct, tol)
        assertEquals(60 - 14, m.saldo)
    }

    @Test
    fun `un tratamiento con todas sus jaulas suspendidas no reporta metricas`() {
        val todas = listOf(jaula("P1", 30, susp = true), jaula("P2", 30, susp = true))
        assertNull(Calculadora.computeMetricasDeParcelas(todas, 1, semanas[0], semanas, d))
    }

    @Test
    fun `la jaula suspendida no entra en la uniformidad`() {
        val conSusp = listOf(jaula("P1", 30), jaula("P2", 30), jaula("P3", 30, susp = true))
        val cv = Calculadora.cvPesoDeParcelas(conSusp, 1, d)!!
        assertEquals("cuenta la jaula suspendida", 2, cv.n)
        assertEquals(200.0, cv.media, tol)
    }

    // ── Cierre de semana y progreso ──────────────────────────────

    private fun partida(susp: Boolean) = Partida(
        id = 1, uid = "u",
        galeras = listOf(Galera("G1", "Galera 1", listOf(Corral("G1-K1", "G1", listOf(
            jaula("P1", 30), jaula("P2", 30, susp = susp)
        )))))
    )

    /** Sin esto, una jaula suspendida bloquearía el cierre de todas las semanas siguientes. */
    @Test
    fun `una jaula suspendida no impide cerrar la semana`() {
        // Solo P1 tiene datos; P2 está vacía.
        val soloP1 = mapOf("P1" to mapOf(1 to DatoParcela(
            1, "P1", peso = 200.0,
            refs = mapOf("BR1" to RefAlimento("BR1", ingreso = 20.0, saldoFin = 1.0))
        )))

        val conActiva = Calculadora.validarSemana(1, partida(susp = false), semanas[0], soloP1)
        assertEquals(2, conActiva.totalParcelas)
        assertEquals(1, conActiva.faltanPeso)
        assertFalse("debería faltar la jaula activa", conActiva.completa)

        val conSuspendida = Calculadora.validarSemana(1, partida(susp = true), semanas[0], soloP1)
        assertEquals("la suspendida sigue contando en el total", 1, conSuspendida.totalParcelas)
        assertEquals(0, conSuspendida.faltanPeso)
        assertTrue("la jaula suspendida está bloqueando el cierre", conSuspendida.completa)
    }

    @Test
    fun `el progreso no cuenta las casillas de una jaula suspendida`() {
        val vacio = emptyMap<String, Map<Int, DatoParcela>>()
        assertEquals(4, Calculadora.calcProgreso(1, partida(susp = false), semanas[0], vacio).total)
        assertEquals(2, Calculadora.calcProgreso(1, partida(susp = true), semanas[0], vacio).total)

        val todas = Calculadora.calcProgresoTodas(partida(susp = true), semanas, vacio)[1]!!
        assertEquals(2, todas.total)
        assertEquals(7, todas.mtotal)
    }
}
