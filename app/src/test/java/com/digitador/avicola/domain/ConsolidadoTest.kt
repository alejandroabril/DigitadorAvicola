package com.digitador.avicola.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La tabla "Consolidado · todas las galeras" del PDF junta, por tratamiento, las jaulas
 * de todas las galeras. El riesgo está en promediar promedios en vez de ponderar por
 * aves: con galeras de distinto tamaño eso da un número equivocado.
 */
class ConsolidadoTest {

    private val tol = 1e-9
    private val semanas = listOf(Semana(1, refsActivas = listOf("BR1")))

    private fun jaula(id: String, aves: Int) = Parcela(id, "K1", inicio = aves, pesoInicio = aves * 40.0)

    /** Datos de la semana 1: sin mortalidad, con peso y una referencia de alimento. */
    private fun datos(vararg pesos: Pair<String, Double>) = pesos.associate { (id, peso) ->
        id to mapOf(
            1 to DatoParcela(
                1, id,
                mort = List(7) { 0 },
                peso = peso,
                refs = mapOf("BR1" to RefAlimento("BR1", ingreso = 20.0, saldoFin = 0.0))
            )
        )
    }

    @Test
    fun `el consolidado pondera por aves, no promedia promedios`() {
        // Galera chica: 2 jaulas de 10 aves a 100 g. Galera grande: 2 de 50 aves a 200 g.
        val chicas = listOf(jaula("G1-K1-P1", 10), jaula("G1-K1-P2", 10))
        val grandes = listOf(jaula("G2-K1-P1", 50), jaula("G2-K1-P2", 50))
        val d = datos(
            "G1-K1-P1" to 100.0, "G1-K1-P2" to 100.0,
            "G2-K1-P1" to 200.0, "G2-K1-P2" to 200.0
        )

        val m = Calculadora.computeMetricasDeParcelas(chicas + grandes, 1, semanas[0], semanas, d)!!

        assertEquals("el saldo del consolidado no suma las dos galeras", 120, m.saldo)

        // (10·100 + 10·100 + 50·200 + 50·200) / 120
        assertEquals(22000.0 / 120.0, m.promPeso, tol)
        assertNotEquals(
            "está promediando los promedios de cada galera en vez de ponderar por aves",
            150.0, m.promPeso, 0.5
        )
    }

    @Test
    fun `consolidar las jaulas de un corral da lo mismo que pedir ese corral`() {
        val parcelas = listOf(jaula("G1-K1-P1", 20), jaula("G1-K1-P2", 30))
        val corral = Corral("G1-K1", "G1", parcelas)
        val d = datos("G1-K1-P1" to 180.0, "G1-K1-P2" to 240.0)

        val porCorral = Calculadora.computeMetricasCorral(corral, 1, semanas[0], semanas, d)
        val porParcelas = Calculadora.computeMetricasDeParcelas(parcelas, 1, semanas[0], semanas, d)
        assertEquals(porCorral, porParcelas)
    }

    @Test
    fun `la columna del lote junta todos los tratamientos`() {
        val k1 = listOf(jaula("G1-K1-P1", 10), jaula("G2-K1-P1", 10))
        val k2 = listOf(jaula("G1-K2-P1", 10), jaula("G2-K2-P1", 10))
        val d = datos(
            "G1-K1-P1" to 100.0, "G2-K1-P1" to 100.0,
            "G1-K2-P1" to 300.0, "G2-K2-P1" to 300.0
        )

        val mK1 = Calculadora.computeMetricasDeParcelas(k1, 1, semanas[0], semanas, d)!!
        val mK2 = Calculadora.computeMetricasDeParcelas(k2, 1, semanas[0], semanas, d)!!
        val mLote = Calculadora.computeMetricasDeParcelas(k1 + k2, 1, semanas[0], semanas, d)!!

        assertEquals(mK1.saldo + mK2.saldo, mLote.saldo)
        assertEquals(100.0, mK1.promPeso, tol)
        assertEquals(300.0, mK2.promPeso, tol)
        // Mismo número de aves en ambos tratamientos → el lote cae en medio.
        assertEquals(200.0, mLote.promPeso, tol)
    }

    @Test
    fun `un grupo sin jaulas no produce metricas`() {
        assertNull(Calculadora.computeMetricasDeParcelas(emptyList(), 1, semanas[0], semanas, emptyMap()))
    }

    @Test
    fun `las jaulas sin aves vivas no arrastran el promedio del consolidado`() {
        val viva = jaula("G1-K1-P1", 20)
        val muerta = jaula("G2-K1-P1", 10)
        val d = mapOf(
            "G1-K1-P1" to mapOf(1 to DatoParcela(1, "G1-K1-P1", mort = List(7) { 0 }, peso = 150.0)),
            // Se le muere toda la jaula: no debe pesar en el promedio, pero sí en la
            // mortalidad acumulada.
            "G2-K1-P1" to mapOf(1 to DatoParcela(1, "G2-K1-P1", mort = listOf(10, 0, 0, 0, 0, 0, 0), peso = 999.0))
        )

        val m = Calculadora.computeMetricasDeParcelas(listOf(viva, muerta), 1, semanas[0], semanas, d)!!
        assertEquals(20, m.saldo)
        assertEquals("la jaula sin aves entró en el promedio", 150.0, m.promPeso, tol)
        // 10 muertas sobre 30 iniciales del conjunto.
        assertEquals(10.0 / 30.0, m.mortAcumPct, tol)
    }
}
