package com.digitador.avicola.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Al purgar un lote se borran sus preferencias. Viven en el MISMO archivo que el PIN y
 * los ajustes globales, así que lo que se selecciona para borrar tiene que ser exacto:
 * una coincidencia de más deja al usuario sin PIN o cambia los cálculos de otro lote.
 */
class ClavesDeLoteTest {

    private val uid = "11111111-2222-3333-4444-555555555555"
    private val otro = "99999999-8888-7777-6666-555555555555"

    private fun prefsDeEjemplo(): Set<String> = setOf(
        // Globales: NUNCA se tocan.
        "pin_habilitado", "pin_valor", "pin_hash", "pin_salt", "modo_por_tratamiento",
        // Del lote a purgar.
        "ultima_semana_$uid",
        "refs_excluidas_${uid}_1",
        "refs_excluidas_${uid}_2",
        "refs_excluidas_${uid}_10",
        // De otro lote: tampoco se tocan.
        "ultima_semana_$otro",
        "refs_excluidas_${otro}_1"
    )

    @Test
    fun `selecciona solo las claves del lote indicado`() {
        val claves = ConfigRepository.clavesDeLote(uid, prefsDeEjemplo())
        assertEquals(
            setOf(
                "ultima_semana_$uid",
                "refs_excluidas_${uid}_1",
                "refs_excluidas_${uid}_2",
                "refs_excluidas_${uid}_10"
            ),
            claves
        )
    }

    @Test
    fun `nunca toca el PIN ni los ajustes globales`() {
        val claves = ConfigRepository.clavesDeLote(uid, prefsDeEjemplo())
        listOf("pin_habilitado", "pin_valor", "pin_hash", "pin_salt", "modo_por_tratamiento")
            .forEach { assertTrue("borraría $it", it !in claves) }
    }

    @Test
    fun `no alcanza a otro lote cuyo uid empiece igual`() {
        val corto = "abc"
        val largo = "abcdef"
        val todas = setOf(
            "ultima_semana_$corto", "refs_excluidas_${corto}_1",
            "ultima_semana_$largo", "refs_excluidas_${largo}_1"
        )
        assertEquals(
            setOf("ultima_semana_$corto", "refs_excluidas_${corto}_1"),
            ConfigRepository.clavesDeLote(corto, todas)
        )
    }

    @Test
    fun `un uid vacio no selecciona nada`() {
        assertTrue(ConfigRepository.clavesDeLote("", prefsDeEjemplo()).isEmpty())
        assertTrue(ConfigRepository.clavesDeLote("   ", prefsDeEjemplo()).isEmpty())
    }

    @Test
    fun `un lote sin preferencias guardadas no selecciona nada`() {
        val soloGlobales = setOf("pin_hash", "modo_por_tratamiento")
        assertTrue(ConfigRepository.clavesDeLote(uid, soloGlobales).isEmpty())
    }
}
