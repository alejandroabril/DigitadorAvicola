package com.digitador.avicola.ui.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitador.avicola.data.repository.ConfigRepository
import com.digitador.avicola.data.repository.DigitadorRepository
import com.digitador.avicola.data.repository.ExportService
import com.digitador.avicola.domain.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val partidas: List<PartidaSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String = ""
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: DigitadorRepository,
    @Suppress("unused") private val exportService: ExportService,
    private val config: ConfigRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui = _ui.asStateFlow()

    /** Config del PIN de bloqueo (para gatear el acceso a la papelera). */
    val pinHabilitado: kotlinx.coroutines.flow.StateFlow<Boolean> = config.pinHabilitado
    fun verificarPin(p: String): Boolean = config.verificarPin(p)

    /** Marca temporal del último refresh exitoso, para evitar recargas redundantes
     *  (la pantalla dispara recargar() en cada re-entrada). */
    @Volatile private var lastRefreshedAt: Long = 0L

    init { recargar(force = true) }

    fun recargar(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Debounce: si el último refresh fue hace menos de 800 ms y no
        // se forzó, no volvemos a pegarle a la BD.
        if (!force && now - lastRefreshedAt < 800L) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            // Purga los lotes cuya retención en papelera (15 días) ya venció.
            repo.purgarVencidas()
            val summaries = repo.getPartidaSummaries()
            _ui.update { it.copy(partidas = summaries, loading = false) }
            lastRefreshedAt = System.currentTimeMillis()
        }
    }

    /**
     * Crea una partida PENDIENTE en BD a partir de un archivo .davi seleccionado
     * desde el FAB (file picker). Tras crearla, refresca el historial y devuelve
     * el id al callback para que la UI navegue al asistente.
     */
    fun cargarDaviDesdeUri(context: android.content.Context, uri: android.net.Uri, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (json.isNullOrBlank()) {
                    _ui.update { it.copy(error = "No se pudo leer el archivo") }
                    return@launch
                }
                val dist = parseDistribucion(json)
                if (dist.isEmpty()) {
                    _ui.update { it.copy(error = "El archivo no contiene una distribución válida") }
                    return@launch
                }
                val newId = repo.crearPartidaPendiente(dist)
                recargar(force = true)
                onCreated(newId)
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Error al cargar: ${e.message}") }
            }
        }
    }

    fun clearError() = _ui.update { it.copy(error = "") }

    private fun parseDistribucion(json: String): Map<String, Map<String, List<String>>> {
        val gson = Gson()
        val element = JsonParser.parseString(json)
        return if (element.isJsonObject && element.asJsonObject.has("galeras")) {
            val out = linkedMapOf<String, MutableMap<String, List<String>>>()
            element.asJsonObject.getAsJsonArray("galeras").forEach { gEl ->
                val g = gEl.asJsonObject
                val gId = g.get("id").asString
                val cMap = linkedMapOf<String, List<String>>()
                g.getAsJsonArray("corrales").forEach { cEl ->
                    val c = cEl.asJsonObject
                    val tLabel = c.get("id").asString.substringAfterLast("-")
                    val parcelas = c.getAsJsonArray("parcelas").map { it.asJsonObject.get("id").asString }
                    cMap[tLabel] = parcelas
                }
                out[gId] = cMap
            }
            out.mapValues { it.value.toMap() }
        } else {
            val type = object : TypeToken<Map<String, Map<String, List<String>>>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        }
    }

    fun selectPartida(id: Long) {
        repo.setCurrentPartida(id)
    }

    /** Mueve el lote a la papelera (no lo borra: se purga tras 15 días). */
    fun deletePartida(id: Long) {
        viewModelScope.launch {
            repo.moverAPapelera(id)
            recargar(force = true)
        }
    }
}
