package com.digitador.avicola.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Demuestra que unificar el motor de cálculo NO movió ningún número.
 *
 * Genera lotes sintéticos con casos borde (mortalidad que vacía jaulas, pesos sin
 * digitar, ajustes manuales de consumo incluido el 0 explícito, referencias
 * excluidas) y compara, jaula a jaula y corral a corral, el motor unificado
 * ([Calculadora]) contra [CalculadoraLegacy], que es copia literal del código
 * anterior al refactor.
 */
class CalculadoraEquivalenciaTest {

    private val tol = 1e-9

    // ── Generación de lotes sintéticos ───────────────────────────

    private fun lote(rnd: Random, nSemanas: Int): Triple<Corral, List<Semana>, Map<String, Map<Int, DatoParcela>>> {
        val parcelas = (1..6).map { i ->
            Parcela(
                id = "G1-K1-P$i",
                corralId = "G1-K1",
                // Alguna jaula vacía: no debe pesar en los promedios.
                inicio = if (i == 6) 0 else rnd.nextInt(20, 60),
                pesoInicio = rnd.nextDouble(800.0, 2600.0)
            )
        }
        val corral = Corral(id = "G1-K1", galeraId = "G1", parcelas = parcelas)

        val semanas = (1..nSemanas).map { sn ->
            Semana(
                numero = sn,
                refsActivas = when {
                    sn <= 2 -> listOf("BR1")
                    sn <= 4 -> listOf("BR1", "BR2")
                    else -> listOf("BR2", "BR3")
                }
            )
        }

        val datos = parcelas.associate { par ->
            par.id to (1..nSemanas).associateWith { sn ->
                DatoParcela(
                    semanaNumero = sn,
                    parcelaId = par.id,
                    mort = List(7) {
                        when {
                            rnd.nextInt(10) == 0 -> null            // día sin digitar
                            else -> rnd.nextInt(0, 4)
                        }
                    },
                    // Alguna semana sin peso digitado.
                    peso = if (rnd.nextInt(8) == 0) null else rnd.nextDouble(150.0, 3000.0),
                    consAjust = when (rnd.nextInt(12)) {
                        0 -> 0.0                                    // ajuste explícito a CERO
                        1 -> rnd.nextDouble(5.0, 80.0)              // ajuste manual
                        2 -> -1.0                                   // negativo = sin ajuste
                        else -> null
                    },
                    refs = TIPOS_ALIMENTO.associateWith { tipo ->
                        RefAlimento(
                            tipo = tipo,
                            ingreso = if (rnd.nextInt(9) == 0) null else rnd.nextDouble(0.0, 120.0),
                            saldoFin = if (rnd.nextInt(9) == 0) null else rnd.nextDouble(0.0, 40.0)
                        )
                    }
                )
            }
        }
        return Triple(corral, semanas, datos)
    }

    private fun exclusiones(rnd: Random, semanas: List<Semana>): Map<Int, Set<String>> =
        semanas.associate { s ->
            s.numero to if (rnd.nextInt(4) == 0) setOf(s.refsActivas.first()) else emptySet()
        }

    // ── Comparadores ─────────────────────────────────────────────

    private fun igual(campo: String, esperado: Double?, obtenido: Double?) {
        if (esperado == null || obtenido == null) {
            assertEquals("$campo: uno es null y el otro no ($esperado vs $obtenido)", esperado, obtenido)
            return
        }
        assertEquals(campo, esperado, obtenido, tol)
    }

    // ── Tests ────────────────────────────────────────────────────

    @Test
    fun `metricas de corral identicas al motor anterior`() {
        var comparaciones = 0
        for (semilla in 1..200) {
            val rnd = Random(semilla)
            val nSemanas = rnd.nextInt(1, 9)
            val (corral, semanas, datos) = lote(rnd, nSemanas)
            val excl = exclusiones(rnd, semanas)

            for (sem in semanas) {
                val viejo = CalculadoraLegacy.computeMetricasCorral(corral, sem.numero, sem, semanas, datos, excl)
                val nuevo = Calculadora.computeMetricasCorral(corral, sem.numero, sem, semanas, datos, excl)

                if (viejo == null || nuevo == null) {
                    assertEquals("semilla=$semilla sem=${sem.numero}: nulidad distinta", viejo, nuevo)
                    continue
                }
                val ctx = "semilla=$semilla sem=${sem.numero}"
                assertEquals("$ctx saldo", viejo.saldo, nuevo.saldo)
                igual("$ctx promPeso", viejo.promPeso, nuevo.promPeso)
                igual("$ctx consumoGave", viejo.consumoGave, nuevo.consumoGave)
                igual("$ctx consumoAcum", viejo.consumoAcum, nuevo.consumoAcum)
                igual("$ctx fcrSem", viejo.fcrSem, nuevo.fcrSem)
                igual("$ctx fcrAcum", viejo.fcrAcum, nuevo.fcrAcum)
                igual("$ctx mortAcumPct", viejo.mortAcumPct, nuevo.mortAcumPct)
                igual("$ctx gdpSem", viejo.gdpSem, nuevo.gdpSem)
                igual("$ctx gdpLineal", viejo.gdpLineal, nuevo.gdpLineal)
                igual("$ctx cvPeso", viejo.cvPeso, nuevo.cvPeso)
                igual("$ctx cgr", viejo.cgr, nuevo.cgr)
                igual("$ctx fep", viejo.fep, nuevo.fep)
                igual("$ctx fcrAdj", viejo.fcrAdj, nuevo.fcrAdj)
                igual("$ctx ratio", viejo.ratio, nuevo.ratio)
                comparaciones++
            }
        }
        assertTrue("el generador no produjo casos comparables", comparaciones > 500)
    }

    @Test
    fun `filas del Excel identicas al calculo duplicado que vivia en ExportService`() {
        var comparaciones = 0
        for (semilla in 1..200) {
            val rnd = Random(semilla)
            val nSemanas = rnd.nextInt(1, 9)
            val (corral, semanas, datos) = lote(rnd, nSemanas)
            val excl = exclusiones(rnd, semanas)

            for (sem in semanas) for (par in corral.parcelas) {
                val datosByPar = datos[par.id] ?: emptyMap()
                val viejo = CalculadoraLegacy.filaEstadisticaLegacy(par, sem.numero, sem, semanas, datosByPar, excl)
                val m = Calculadora.computeMetricasParcela(par, sem.numero, semanas, datosByPar, excl)

                val ctx = "semilla=$semilla sem=${sem.numero} ${par.id}"
                assertEquals("$ctx inicio", viejo["inicio"]!!, m.saldoAnterior.toDouble(), tol)
                assertEquals("$ctx mort", viejo["mort"]!!, m.mortSem.toDouble(), tol)
                assertEquals("$ctx saldo", viejo["saldo"]!!, m.saldo.toDouble(), tol)
                assertEquals("$ctx peso", viejo["peso"]!!, m.pesoGave, tol)
                assertEquals("$ctx consSem", viejo["consSem"]!!, m.consGave, tol)
                assertEquals("$ctx consAcum", viejo["consAcum"]!!, m.consAcumGave, tol)
                assertEquals("$ctx fcrSem", viejo["fcrSem"]!!, m.fcrSem ?: 0.0, tol)
                assertEquals("$ctx fcrAcum", viejo["fcrAcum"]!!, m.fcrAcum ?: 0.0, tol)
                assertEquals("$ctx gdpSem", viejo["gdpSem"]!!, m.gdpSem, tol)
                assertEquals("$ctx gdpLin", viejo["gdpLin"]!!, m.gdpLineal, tol)
                assertEquals("$ctx mortPct", viejo["mortPct"]!!, m.mortPct, tol)
                assertEquals("$ctx mortAcumPct", viejo["mortAcumPct"]!!, m.mortAcumPct, tol)
                assertEquals("$ctx ratio", viejo["ratio"]!!, m.ratio ?: 0.0, tol)
                assertEquals("$ctx fcrAdj20", viejo["fcrAdj20"]!!,
                    m.fcrAjustado(Calculadora.FCR_ADJ_OBJETIVO_2_0) ?: 0.0, tol)
                assertEquals("$ctx fcrAdj25", viejo["fcrAdj25"]!!,
                    m.fcrAjustado(Calculadora.FCR_ADJ_OBJETIVO_2_5) ?: 0.0, tol)
                assertEquals("$ctx fcrAdj27", viejo["fcrAdj27"]!!,
                    m.fcrAjustado(Calculadora.FCR_ADJ_OBJETIVO_2_7) ?: 0.0, tol)
                comparaciones++
            }
        }
        assertTrue("el generador no produjo casos comparables", comparaciones > 2000)
    }

    /**
     * La pantalla agrega por corral las MISMAS métricas de jaula que escribe el Excel:
     * el saldo del corral tiene que ser la suma de los saldos de sus jaulas vivas.
     */
    @Test
    fun `el corral es exactamente la agregacion de sus jaulas`() {
        var semanasConAves = 0
        for (semilla in 1..40) {
            val rnd = Random(semilla)
            val (corral, semanas, datos) = lote(rnd, 6)
            val excl = exclusiones(rnd, semanas)

            for (sem in semanas) {
                val corralM = Calculadora.computeMetricasCorral(corral, sem.numero, sem, semanas, datos, excl)
                val jaulas = corral.parcelas.map {
                    Calculadora.computeMetricasParcela(it, sem.numero, semanas, datos[it.id] ?: emptyMap(), excl)
                }
                val vivas = jaulas.filter { it.saldo > 0 }
                val ctx = "semilla=$semilla sem=${sem.numero}"

                if (vivas.isEmpty()) {
                    // Sin aves vivas el corral no reporta métricas.
                    assertNull("$ctx: sin jaulas vivas el corral debería ser null", corralM)
                    continue
                }
                assertNotNull("$ctx: hay jaulas vivas pero el corral es null", corralM)
                semanasConAves++

                assertEquals("$ctx saldo", vivas.sumOf { it.saldo }, corralM!!.saldo)
                // Promedio de peso ponderado por saldo, recalculado a mano.
                val esperado = vivas.filter { it.pesoGave > 0 }.sumOf { it.pesoGave * it.saldo } /
                    vivas.sumOf { it.saldo }
                assertEquals("$ctx promPeso", esperado, corralM.promPeso, tol)
                // Mortalidad acumulada: TODAS las jaulas cuentan, vivas o no.
                val mortEsperada = jaulas.sumOf { it.mortAcum }.toDouble() / jaulas.sumOf { it.inicioLote }
                assertEquals("$ctx mortAcumPct", mortEsperada, corralM.mortAcumPct, tol)
            }
        }
        assertTrue("el generador no produjo semanas con aves vivas", semanasConAves > 50)
    }

    /** Un ajuste manual de 0 kg significa "no consumió", no "calcúlalo por referencias". */
    @Test
    fun `ajuste de consumo en cero se respeta`() {
        val par = Parcela(id = "P1", corralId = "K1", inicio = 10, pesoInicio = 400.0)
        val semanas = listOf(Semana(numero = 1, refsActivas = listOf("BR1")))
        val refs = mapOf("BR1" to RefAlimento("BR1", ingreso = 50.0, saldoFin = 10.0))

        val conAjusteCero = mapOf(1 to DatoParcela(1, "P1", peso = 500.0, consAjust = 0.0, refs = refs))
        val sinAjuste = mapOf(1 to DatoParcela(1, "P1", peso = 500.0, consAjust = null, refs = refs))

        assertEquals(0.0, Calculadora.computeMetricasParcela(par, 1, semanas, conAjusteCero).consGave, tol)
        // 40 kg entre 10 aves = 4000 g/ave
        assertEquals(4000.0, Calculadora.computeMetricasParcela(par, 1, semanas, sinAjuste).consGave, tol)
    }

    /** Una referencia excluida no debe entrar en el consumo de esa semana. */
    @Test
    fun `referencia excluida no suma al consumo`() {
        val par = Parcela(id = "P1", corralId = "K1", inicio = 10, pesoInicio = 400.0)
        val semanas = listOf(Semana(numero = 1, refsActivas = listOf("BR1", "BR2")))
        val datos = mapOf(
            1 to DatoParcela(
                1, "P1", peso = 500.0,
                refs = mapOf(
                    "BR1" to RefAlimento("BR1", ingreso = 50.0, saldoFin = 10.0),
                    "BR2" to RefAlimento("BR2", ingreso = 30.0, saldoFin = 0.0)
                )
            )
        )
        // Sin excluir: (50-10) + (30-0) = 70 kg → 7000 g/ave
        assertEquals(7000.0, Calculadora.computeMetricasParcela(par, 1, semanas, datos).consGave, tol)
        // Excluyendo BR2: 40 kg → 4000 g/ave
        assertEquals(
            4000.0,
            Calculadora.computeMetricasParcela(par, 1, semanas, datos, mapOf(1 to setOf("BR2"))).consGave,
            tol
        )
    }
}
