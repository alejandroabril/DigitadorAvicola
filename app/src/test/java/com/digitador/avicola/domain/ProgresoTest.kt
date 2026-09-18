package com.digitador.avicola.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * `calcProgresoTodas` reemplaza a llamar `calcProgreso` semana por semana, recorriendo
 * el lote una sola vez. Estos tests fijan que devuelva exactamente lo mismo.
 */
class ProgresoTest {

    private fun lote(rnd: Random, nGaleras: Int, nSemanas: Int): Triple<Partida, List<Semana>, Map<String, Map<Int, DatoParcela>>> {
        val galeras = (1..nGaleras).map { gi ->
            Galera("G$gi", "Galera $gi", (1..4).map { ki ->
                Corral("G$gi-K$ki", "G$gi", (1..4).map { pi ->
                    Parcela(
                        id = "G$gi-K$ki-P$pi",
                        corralId = "G$gi-K$ki",
                        // Alguna jaula sin aves: calcProgreso la ignora.
                        inicio = if (pi == 4 && ki == 1) 0 else rnd.nextInt(20, 50),
                        pesoInicio = rnd.nextDouble(700.0, 1300.0)
                    )
                })
            })
        }
        val partida = Partida(id = 1, numero = "P-1", uid = "uid-1", galeras = galeras)

        val semanas = (1..nSemanas).map { sn ->
            Semana(numero = sn, refsActivas = if (sn % 2 == 0) listOf("BR1", "BR2") else listOf("BR1"))
        }

        val datos = galeras.flatMap { it.corrales }.flatMap { it.parcelas }.associate { par ->
            par.id to (1..nSemanas).mapNotNull { sn ->
                // Alguna semana sin ningún dato para esa jaula.
                if (rnd.nextInt(7) == 0) null
                else sn to DatoParcela(
                    semanaNumero = sn,
                    parcelaId = par.id,
                    mort = List(7) { if (rnd.nextInt(3) == 0) null else rnd.nextInt(0, 3) },
                    peso = if (rnd.nextInt(3) == 0) null else rnd.nextDouble(200.0, 2800.0),
                    refs = TIPOS_ALIMENTO.associateWith { tipo ->
                        RefAlimento(
                            tipo = tipo,
                            ingreso = if (rnd.nextInt(3) == 0) null else rnd.nextDouble(10.0, 90.0),
                            saldoFin = rnd.nextDouble(0.0, 20.0)
                        )
                    }
                )
            }.toMap()
        }
        return Triple(partida, semanas, datos)
    }

    @Test
    fun `una sola pasada da el mismo progreso que calcular semana por semana`() {
        var comparaciones = 0
        for (semilla in 1..50) {
            val rnd = Random(semilla)
            val nSemanas = rnd.nextInt(1, 10)
            val (partida, semanas, datos) = lote(rnd, rnd.nextInt(1, 4), nSemanas)

            val todas = Calculadora.calcProgresoTodas(partida, semanas, datos)
            assertEquals("semilla=$semilla: faltan semanas en el resultado", semanas.size, todas.size)

            for (s in semanas) {
                val uno = Calculadora.calcProgreso(s.numero, partida, s, datos)
                val ctx = "semilla=$semilla sem=${s.numero}"
                assertEquals("$ctx", uno, todas[s.numero])
                comparaciones++
            }
        }
        assertTrue("el generador no produjo casos comparables", comparaciones > 150)
    }

    @Test
    fun `las jaulas sin aves no cuentan para el progreso`() {
        val conAves = Parcela("P1", "K1", inicio = 30, pesoInicio = 900.0)
        val vacia = Parcela("P2", "K1", inicio = 0, pesoInicio = 0.0)
        val partida = Partida(
            id = 1, uid = "u",
            galeras = listOf(Galera("G1", "G1", listOf(Corral("G1-K1", "G1", listOf(conAves, vacia)))))
        )
        val semanas = listOf(Semana(1, refsActivas = listOf("BR1")))

        val prog = Calculadora.calcProgresoTodas(partida, semanas, emptyMap())[1]!!
        // Solo la jaula con aves aporta: 2 casillas (peso + alimento) y 7 días de mortalidad.
        assertEquals(2, prog.total)
        assertEquals(7, prog.mtotal)
        assertEquals(0, prog.filled)
        assertEquals(0, prog.pct)
    }

    @Test
    fun `una semana totalmente digitada da cien por ciento`() {
        val par = Parcela("P1", "K1", inicio = 30, pesoInicio = 900.0)
        val partida = Partida(
            id = 1, uid = "u",
            galeras = listOf(Galera("G1", "G1", listOf(Corral("G1-K1", "G1", listOf(par)))))
        )
        val semanas = listOf(Semana(1, refsActivas = listOf("BR1")))
        val datos = mapOf(
            "P1" to mapOf(
                1 to DatoParcela(
                    1, "P1",
                    mort = List(7) { 0 },
                    peso = 450.0,
                    refs = mapOf("BR1" to RefAlimento("BR1", ingreso = 40.0, saldoFin = 5.0))
                )
            )
        )
        val prog = Calculadora.calcProgresoTodas(partida, semanas, datos)[1]!!
        assertEquals(100, prog.pct)
        assertEquals(100, prog.mpct)
        assertEquals(EstadoSemana.COMPLETA, prog.status)
    }

    @Test
    fun `un lote sin semanas no produce progreso`() {
        val partida = Partida(id = 1, uid = "u", galeras = emptyList())
        assertTrue(Calculadora.calcProgresoTodas(partida, emptyList(), emptyMap()).isEmpty())
    }
}
