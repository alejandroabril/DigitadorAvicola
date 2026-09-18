package com.digitador.avicola.data

import com.digitador.avicola.domain.Semana
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Importación de archivos `.davi`. Es la única puerta por la que entran datos a la app
 * desde fuera, así que un fallo aquí corrompe un ensayo entero.
 */
@RunWith(RobolectricTestRunner::class)
class ImportarDaviTest {

    private lateinit var e: EntornoDePrueba

    @Before fun montar() { e = EntornoDePrueba() }
    @After fun desmontar() { e.cerrar() }

    /** Deja un lote digitado y devuelve el JSON que produce el export. */
    private suspend fun loteExportado(numero: String = "3580", uid: String = "uid-origen"): String {
        e.repo.guardarPartida(e.loteDeEjemplo(numero = numero, uid = uid, galeras = 1, corrales = 2, parcelas = 2))
        e.repo.upsertSemana(Semana(numero = 1, fechaInicio = "2026-01-01", fechaFin = "2026-01-07", refsActivas = listOf("BR1")))
        e.repo.upsertSemana(Semana(numero = 2, fechaInicio = "2026-01-08", fechaFin = "2026-01-14", refsActivas = listOf("BR1", "BR2")))
        e.repo.savePeso(1, "G1-K1-P1", 185.0)
        e.repo.saveMortalidad(1, "G1-K1-P1", listOf(1, 0, 0, 2, null, 0, 0))
        e.repo.saveRefAlimento(1, "G1-K1-P1", "BR1", ingreso = 30.0, saldoFin = 4.0)
        e.repo.savePeso(2, "G1-K1-P1", 450.0)
        e.repo.saveConsAjust(2, "G1-K1-P1", 0.0)   // ajuste explícito a cero
        e.repo.savePeso(1, "G1-K2-P1", 190.0)
        return e.export.exportarLote(e.context).readText()
    }

    // ── Reconocimiento de formato ────────────────────────────────

    @Test
    fun `reconoce un backup completo y descarta lo que no lo es`() = runBlocking {
        val json = loteExportado()
        assertTrue(e.export.esBackupCompleto(json))
        assertEquals("uid-origen", e.export.uidDeBackup(json))

        assertFalse(e.export.esBackupCompleto("""{"distribucion":{"G1":{"K1":["P1"]}}}"""))
        assertFalse(e.export.esBackupCompleto("no soy json"))
        assertFalse(e.export.esBackupCompleto(""))
        assertEquals("", e.export.uidDeBackup("no soy json"))
    }

    @Test
    fun `un JSON con formato desconocido falla sin crear nada`() = runBlocking {
        val r = e.export.importarDesdeJson("""{"cualquier":"cosa"}""")
        assertTrue("debería fallar", r.isFailure)
        assertTrue("dejó un lote fantasma", e.repo.getPartidaSummaries().isEmpty())
    }

    @Test
    fun `un JSON roto falla sin reventar`() = runBlocking {
        val r = e.export.importarDesdeJson("{ esto no cierra")
        assertTrue(r.isFailure)
        assertTrue(e.repo.getPartidaSummaries().isEmpty())
    }

    // ── Ida y vuelta del formato propio ──────────────────────────

    @Test
    fun `exportar e importar como copia reproduce el lote entero`() = runBlocking {
        val json = loteExportado()

        val nuevoId = e.export.importarDesdeJson(json, comoCopia = true).getOrThrow()
        val copia = e.repo.cargarEstado(nuevoId)
        val p = copia.partida!!

        // Estructura
        assertEquals(1, p.galeras.size)
        assertEquals(2, p.galeras[0].corrales.size)
        assertEquals(4, e.jaulas(p).size)
        assertEquals(40, e.jaulas(p).first().inicio)

        // Semanas, con sus referencias activas
        assertEquals(listOf(1, 2), copia.semanas.map { it.numero })
        assertEquals(listOf("BR1", "BR2"), copia.getSemana(2)!!.refsActivas)
        assertEquals("2026-01-07", copia.getSemana(1)!!.fechaFin)

        // Datos digitados
        val d1 = copia.datosPorParcela["G1-K1-P1"]!![1]!!
        assertEquals(185.0, d1.peso!!, 1e-9)
        assertEquals(listOf(1, 0, 0, 2, null, 0, 0), d1.mort)
        assertEquals(30.0, d1.refs["BR1"]!!.ingreso!!, 1e-9)
        assertEquals(4.0, d1.refs["BR1"]!!.saldoFin!!, 1e-9)

        // El ajuste de consumo a CERO tiene que sobrevivir como 0.0, no como "sin ajuste":
        // es la diferencia entre "no consumió" y "calcúlalo por referencias".
        assertEquals(0.0, copia.datosPorParcela["G1-K1-P1"]!![2]!!.consAjust!!, 1e-9)

        assertEquals(190.0, copia.datosPorParcela["G1-K2-P1"]!![1]!!.peso!!, 1e-9)
    }

    @Test
    fun `una copia recibe uid y numero propios para no pisar el original`() = runBlocking {
        val json = loteExportado(numero = "3580", uid = "uid-origen")
        val nuevoId = e.export.importarDesdeJson(json, comoCopia = true).getOrThrow()

        val copia = e.repo.cargarEstado(nuevoId).partida!!
        assertNotEquals("la copia reusa el uid del original", "uid-origen", copia.uid)
        assertTrue("la copia se quedó sin uid", copia.uid.isNotBlank())
        assertNotEquals("la copia reusa el número del original", "3580", copia.numero)

        assertEquals("deberían convivir los dos lotes", 2, e.repo.getPartidaSummaries().size)
    }

    @Test
    fun `importar sobre un numero ya existente genera copia igualmente`() = runBlocking {
        val json = loteExportado(numero = "3580", uid = "uid-origen")

        // Sin pedir copia: como el número ya existe, no puede repetirlo.
        val nuevoId = e.export.importarDesdeJson(json, comoCopia = false).getOrThrow()
        val copia = e.repo.cargarEstado(nuevoId).partida!!
        assertNotEquals("3580", copia.numero)
        assertEquals(2, e.repo.getPartidaSummaries().size)
    }

    @Test
    fun `importar en una app vacia conserva numero y uid originales`() = runBlocking {
        val json = loteExportado(numero = "3580", uid = "uid-origen")
        // Se vacía la base para simular el teléfono de destino.
        e.repo.getPartidaSummaries().forEach { e.repo.borrarFisicamente(it.id) }
        assertTrue(e.repo.getPartidaSummaries().isEmpty())

        val id = e.export.importarDesdeJson(json, comoCopia = false).getOrThrow()
        val p = e.repo.cargarEstado(id).partida!!
        assertEquals("3580", p.numero)
        assertEquals("uid-origen", p.uid)
    }

    // ── Formato experimental ─────────────────────────────────────

    private val jsonExperimental = """
    {
      "partida": {
        "numero": "EXP-1", "lote": "LE", "edad": "1d", "fechaInicio": "2026-02-01",
        "galeras": [{
          "id": "G1", "nombre": "Galera 1",
          "corrales": [{
            "id": "G1-K1",
            "parcelas": [
              {"id": "G1-K1-P1", "inicio": 25, "pesoInicio": 1000.0},
              {"id": "G1-K1-P2", "inicio": 30, "pesoInicio": 1200.0}
            ]
          }]
        }]
      },
      "semanas": [{
        "numero": 1, "fechaInicio": "2026-02-01", "fechaFin": "2026-02-07",
        "refs": ["BR1"],
        "datos": {
          "G1": { "G1-K1": {
            "G1-K1-P1": {
              "mort": [0,1,0,0,0,0,0], "peso": 175.5, "consAjust": null,
              "refs": { "BR1": {"ingreso": 22.0, "saldoFin": 3.0} }
            }
          }}
        }
      }]
    }
    """.trimIndent()

    @Test
    fun `importa el formato experimental con su mapa anidado de datos`() = runBlocking {
        val id = e.export.importarDesdeJson(jsonExperimental).getOrThrow()
        val estado = e.repo.cargarEstado(id)
        val p = estado.partida!!

        assertEquals("EXP-1", p.numero)
        assertTrue("el formato experimental no trae uid: debe generarse", p.uid.isNotBlank())
        assertEquals(2, e.jaulas(p).size)
        assertEquals(25, e.jaulas(p).first().inicio)

        assertEquals(listOf("BR1"), estado.getSemana(1)!!.refsActivas)
        val d = estado.datosPorParcela["G1-K1-P1"]!![1]!!
        assertEquals(175.5, d.peso!!, 1e-9)
        assertEquals(listOf(0, 1, 0, 0, 0, 0, 0), d.mort)
        assertEquals(22.0, d.refs["BR1"]!!.ingreso!!, 1e-9)

        // La jaula sin datos digitados existe, pero sin dato de esa semana.
        assertNull(estado.datosPorParcela["G1-K1-P2"]?.get(1)?.peso)
    }

    // ── Robustez ─────────────────────────────────────────────────

    /**
     * Si el archivo trae datos de una jaula que no está en su propia distribución, la
     * importación falla a media escritura. No debe quedar un lote fantasma a medias.
     */
    @Test
    fun `un backup incoherente no deja un lote a medias`() = runBlocking {
        val json = loteExportado()
        // Solo se corrompe la sección de datos: la distribución queda intacta, así que
        // el archivo se refiere a una jaula que su propio lote no tiene.
        val corte = json.indexOf("\"datosPorParcela\"")
        assertTrue("el export cambió de forma", corte > 0)
        val roto = json.substring(0, corte) +
            json.substring(corte).replace("G1-K1-P1", "JAULA-QUE-NO-EXISTE")

        val r = e.export.importarDesdeJson(roto, comoCopia = true)
        assertTrue("debería fallar con datos incoherentes", r.isFailure)
        assertEquals(
            "quedó un lote fantasma de la importación fallida",
            1, e.repo.getPartidaSummaries().size   // solo el original
        )
    }
}
