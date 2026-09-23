package com.digitador.avicola.data

import com.digitador.avicola.data.repository.ConfigRepository
import com.digitador.avicola.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Repositorio contra una base Room real (en memoria). */
@RunWith(RobolectricTestRunner::class)
class DigitadorRepositorioTest {

    private lateinit var e: EntornoDePrueba

    @Before fun montar() { e = EntornoDePrueba() }
    @After fun desmontar() { e.cerrar() }

    @Test
    fun `guardar y recargar un lote conserva toda la jerarquia`() = runBlocking {
        val original = e.loteDeEjemplo(galeras = 2, corrales = 3, parcelas = 4)
        val id = e.repo.guardarPartida(original)

        val estado = e.repo.cargarEstado(id)
        val p = assertNotNull("no se recargó la partida", estado.partida).let { estado.partida!! }

        assertEquals("P-100", p.numero)
        assertEquals("uid-de-prueba", p.uid)
        assertEquals(2, p.galeras.size)
        assertEquals(3, p.galeras[0].corrales.size)
        assertEquals(4, p.galeras[0].corrales[0].parcelas.size)
        assertEquals(24, e.jaulas(p).size)

        val jaula = p.galeras[0].corrales[0].parcelas[0]
        assertEquals("G1-K1-P1", jaula.id)
        assertEquals(40, jaula.inicio)
        assertEquals(1600.0, jaula.pesoInicio, 1e-9)
    }

    @Test
    fun `los datos digitados sobreviven a una recarga`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo(galeras = 1, corrales = 1, parcelas = 2))
        e.repo.upsertSemana(Semana(numero = 1, refsActivas = listOf("BR1", "BR2")))

        e.repo.savePeso(1, "G1-K1-P1", 185.5)
        e.repo.saveMortalidad(1, "G1-K1-P1", listOf(1, 0, 2, null, 0, 0, 1))
        e.repo.saveConsAjust(1, "G1-K1-P1", 12.5)
        e.repo.saveRefAlimento(1, "G1-K1-P1", "BR1", ingreso = 30.0, saldoFin = 4.5)

        val estado = e.repo.cargarEstado(id)
        val d = estado.datosPorParcela["G1-K1-P1"]?.get(1)
        assertNotNull("no se guardó el dato de la jaula", d)
        assertEquals(185.5, d!!.peso!!, 1e-9)
        assertEquals(listOf(1, 0, 2, null, 0, 0, 1), d.mort)
        assertEquals(12.5, d.consAjust!!, 1e-9)
        assertEquals(30.0, d.refs["BR1"]!!.ingreso!!, 1e-9)
        assertEquals(4.5, d.refs["BR1"]!!.saldoFin!!, 1e-9)

        assertEquals(listOf("BR1", "BR2"), estado.getSemana(1)!!.refsActivas)
    }

    @Test
    fun `un peso nulo borra el valor en vez de dejar el anterior`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo(galeras = 1, corrales = 1, parcelas = 1))
        e.repo.upsertSemana(Semana(numero = 1))
        e.repo.savePeso(1, "G1-K1-P1", 200.0)
        e.repo.savePeso(1, "G1-K1-P1", null)

        val d = e.repo.cargarEstado(id).datosPorParcela["G1-K1-P1"]?.get(1)
        assertNull("el peso borrado quedó con el valor viejo", d?.peso)
    }

    @Test
    fun `la papelera oculta el lote sin borrarlo y lo devuelve al restaurar`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo())
        e.repo.moverAPapelera(id)

        assertTrue("el lote en papelera sigue en el historial",
            e.repo.getPartidaSummaries().none { it.id == id })
        assertEquals(1, e.repo.getPapeleraSummaries().size)
        assertEquals(id, e.repo.getPapeleraSummaries().first().id)

        e.repo.restaurarDePapelera(id)
        assertTrue("el lote restaurado no volvió al historial",
            e.repo.getPartidaSummaries().any { it.id == id })
        assertTrue(e.repo.getPapeleraSummaries().isEmpty())
    }

    @Test
    fun `la purga solo se lleva los lotes cuya retencion vencio`() = runBlocking {
        val viejo = e.repo.guardarPartida(e.loteDeEjemplo(numero = "P-VIEJO", uid = "uid-viejo"))
        val reciente = e.repo.guardarPartida(e.loteDeEjemplo(numero = "P-NUEVO", uid = "uid-nuevo"))
        e.repo.moverAPapelera(viejo)
        e.repo.moverAPapelera(reciente)

        // Retención 0 días: purga lo que lleve más de 0 ms en la papelera. Se comprueba
        // con retención muy alta que NO se lleve nada, y con 0 que se los lleve.
        e.repo.purgarVencidas(diasRetencion = 3650)
        assertEquals("purgó lotes todavía en retención", 2, e.repo.getPapeleraSummaries().size)

        e.repo.purgarVencidas(diasRetencion = 0)
        assertTrue("no purgó los lotes vencidos", e.repo.getPapeleraSummaries().isEmpty())
        assertTrue(e.repo.getPartidaSummaries().isEmpty())
    }

    @Test
    fun `el borrado definitivo se lleva semanas y datos, no solo la partida`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo(galeras = 1, corrales = 1, parcelas = 2))
        e.repo.upsertSemana(Semana(numero = 1))
        e.repo.savePeso(1, "G1-K1-P1", 190.0)
        e.repo.saveRefAlimento(1, "G1-K1-P1", "BR1", 20.0, 3.0)

        e.repo.borrarFisicamente(id)

        val estado = e.repo.cargarEstado(id)
        assertNull("la partida sigue ahí", estado.partida)
        assertTrue(estado.semanas.isEmpty())
        assertTrue(estado.datosPorParcela.isEmpty())
        assertEquals(0, db_countDatos())
        assertEquals(0, db_countRefs())
    }

    /**
     * El borrado definitivo también limpia las preferencias del lote, sin tocar las
     * globales. Es la contraparte integrada de ClavesDeLoteTest.
     */
    @Test
    fun `el borrado definitivo limpia las preferencias del lote y respeta el PIN`() = runBlocking {
        val uid = "uid-a-purgar"
        val id = e.repo.guardarPartida(e.loteDeEjemplo(uid = uid))

        e.config.setUltimaSemana(uid, 4)
        e.config.setRefExcluida(uid, 1, "BR1", true)
        e.config.setRefExcluida(uid, 2, "BR2", true)
        e.config.setPinHabilitado(true)
        assertTrue(e.config.cambiarPin(ConfigRepository.DEFAULT_PIN, "4321"))

        // Un segundo lote: sus preferencias no se pueden ver afectadas.
        val otroUid = "uid-que-se-queda"
        e.config.setUltimaSemana(otroUid, 7)

        e.repo.borrarFisicamente(id)

        assertEquals("quedó la última semana del lote purgado", 0, e.config.getUltimaSemana(uid))
        assertTrue("quedaron referencias excluidas", e.config.refsExcluidas(uid, 1).isEmpty())
        assertTrue(e.config.refsExcluidas(uid, 2).isEmpty())

        assertEquals("se llevó por delante otro lote", 7, e.config.getUltimaSemana(otroUid))
        assertTrue("se llevó por delante el PIN", e.config.verificarPin("4321"))
        assertTrue(e.config.pinHabilitado.value)
    }

    @Test
    fun `el numero de copia no pisa uno existente`() = runBlocking {
        e.repo.guardarPartida(e.loteDeEjemplo(numero = "3580", uid = "u1"))
        assertTrue(e.repo.existeOtraPartidaConNumero("3580", -1L))
        assertFalse(e.repo.existeOtraPartidaConNumero("9999", -1L))

        val copia1 = e.repo.generarNumeroCopia("3580")
        assertNotEquals("3580", copia1)
        e.repo.guardarPartida(e.loteDeEjemplo(numero = copia1, uid = "u2"))

        val copia2 = e.repo.generarNumeroCopia("3580")
        assertNotEquals("la segunda copia repite el número de la primera", copia1, copia2)
        assertFalse(e.repo.existeOtraPartidaConNumero(copia2, -1L))
    }

    @Test
    fun `borrar una semana no toca las demas`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo(galeras = 1, corrales = 1, parcelas = 1))
        e.repo.upsertSemana(Semana(numero = 1))
        e.repo.upsertSemana(Semana(numero = 2))
        e.repo.savePeso(1, "G1-K1-P1", 100.0)
        e.repo.savePeso(2, "G1-K1-P1", 300.0)

        e.repo.borrarSemana(2)

        val estado = e.repo.cargarEstado(id)
        assertEquals(listOf(1), estado.semanas.map { it.numero })
        assertEquals(100.0, estado.datosPorParcela["G1-K1-P1"]!![1]!!.peso!!, 1e-9)
        assertNull("quedaron datos de la semana borrada", estado.datosPorParcela["G1-K1-P1"]?.get(2))
    }

    @Test
    fun `cerrar y reabrir una semana cambia solo esa semana`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo(galeras = 1, corrales = 1, parcelas = 1))
        e.repo.upsertSemana(Semana(numero = 1))
        e.repo.upsertSemana(Semana(numero = 2))

        e.repo.cerrarSemana(1)
        var estado = e.repo.cargarEstado(id)
        assertTrue(estado.getSemana(1)!!.cerrada)
        assertFalse(estado.getSemana(2)!!.cerrada)

        e.repo.reabrirSemana(1)
        estado = e.repo.cargarEstado(id)
        assertFalse(estado.getSemana(1)!!.cerrada)
    }

    // ── Desglose de pesadas ──────────────────────────────────

    /**
     * Las aves se pesan por grupos (la balanza no aguanta la jaula entera) pero se pesan
     * TODAS: la suma es el peso total y el promedio sale de dividirla entre las vivas.
     * El desglose se guarda para que el registro muestre cómo se llegó al total.
     */
    @Test
    fun `el desglose de pesadas sobrevive a la recarga`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo(galeras = 1, corrales = 1, parcelas = 1))
        e.repo.upsertSemana(Semana(numero = 2))

        val pesadas = listOf(1850.0, 1790.0, 1905.0)   // tres grupos
        val saldo = 40
        e.repo.savePeso(2, "G1-K1-P1", pesadas.sum() / saldo, pesadas)

        val d = e.repo.cargarEstado(id).datosPorParcela["G1-K1-P1"]!![2]!!
        assertEquals("se perdió el desglose", pesadas, d.pesos)
        assertEquals(5545.0 / 40, d.peso!!, 1e-9)
    }

    /** Guardar solo el promedio deja el desglose vacío, no el anterior. */
    @Test
    fun `guardar un peso sin desglose no arrastra el anterior`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo(galeras = 1, corrales = 1, parcelas = 1))
        e.repo.upsertSemana(Semana(numero = 1))

        e.repo.savePeso(1, "G1-K1-P1", 140.0, listOf(1850.0, 1790.0))
        e.repo.savePeso(1, "G1-K1-P1", 150.0)

        val d = e.repo.cargarEstado(id).datosPorParcela["G1-K1-P1"]!![1]!!
        assertEquals(150.0, d.peso!!, 1e-9)
        assertTrue("quedó un desglose que ya no explica el total", d.pesos.isEmpty())
    }

    // ── Suspensión de jaulas ─────────────────────────────────

    @Test
    fun `suspender una jaula se guarda con su motivo y sobrevive a la recarga`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo(galeras = 1, corrales = 1, parcelas = 3))

        e.repo.setParcelaSuspendida("G1-K1-P2", true, "  mortalidad por golpe de calor  ")

        val jaulas = e.jaulas(e.repo.cargarEstado(id).partida!!)
        val susp = jaulas.first { it.id == "G1-K1-P2" }
        assertTrue(susp.suspendida)
        assertEquals("el motivo no se recortó", "mortalidad por golpe de calor", susp.suspendidaMotivo)
        assertTrue("no se registró cuándo", susp.suspendidaEn > 0)

        assertTrue("arrastró a las demás", jaulas.filter { it.id != "G1-K1-P2" }.none { it.suspendida })
    }

    @Test
    fun `reactivar limpia el motivo y la fecha`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo(galeras = 1, corrales = 1, parcelas = 2))
        e.repo.setParcelaSuspendida("G1-K1-P1", true, "se comprometió")
        e.repo.setParcelaSuspendida("G1-K1-P1", false)

        val p = e.jaulas(e.repo.cargarEstado(id).partida!!).first { it.id == "G1-K1-P1" }
        assertFalse(p.suspendida)
        assertEquals("quedó el motivo de una suspensión ya levantada", "", p.suspendidaMotivo)
        assertEquals(0L, p.suspendidaEn)
    }

    /** Guardar el lote de nuevo (editar la recepción, reimportar) no puede perder la suspensión. */
    @Test
    fun `volver a guardar el lote conserva las jaulas suspendidas`() = runBlocking {
        val id = e.repo.guardarPartida(e.loteDeEjemplo(galeras = 1, corrales = 1, parcelas = 2))
        e.repo.setParcelaSuspendida("G1-K1-P1", true, "motivo")

        val estado = e.repo.cargarEstado(id)
        e.repo.guardarPartida(estado.partida!!)

        val p = e.jaulas(e.repo.cargarEstado(id).partida!!).first { it.id == "G1-K1-P1" }
        assertTrue("guardar el lote borró la suspensión", p.suspendida)
        assertEquals("motivo", p.suspendidaMotivo)
    }

    private fun db_countDatos(): Int = e.db.query("SELECT COUNT(*) FROM dato_parcela", null)
        .use { it.moveToFirst(); it.getInt(0) }

    private fun db_countRefs(): Int = e.db.query("SELECT COUNT(*) FROM ref_alimento", null)
        .use { it.moveToFirst(); it.getInt(0) }
}
