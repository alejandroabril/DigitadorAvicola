package com.digitador.avicola.ui.screen.ajustes

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitador.avicola.data.repository.DigitadorRepository
import com.digitador.avicola.data.repository.ExportService
import com.digitador.avicola.domain.Partida
import com.digitador.avicola.domain.Semana
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AjustesUiState(
    val partida: Partida? = null,
    val semanas: List<Semana> = emptyList(),
    val loading: Boolean = true,
    val mensaje: String = "",   // mensaje informativo o de error
    val mensajeEsError: Boolean = false,
    val procesando: Boolean = false
)

@HiltViewModel
class AjustesViewModel @Inject constructor(
    val repo: DigitadorRepository,
    val exportService: ExportService
) : ViewModel() {

    private val _ui = MutableStateFlow(AjustesUiState())
    val ui = _ui.asStateFlow()

    fun cargar() {
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val state = repo.cargarEstado()
                _ui.update { it.copy(partida = state.partida, semanas = state.semanas, loading = false) }
            } catch (e: Exception) {
                e.printStackTrace()
                _ui.update { it.copy(loading = false, mensaje = "Error al cargar el lote", mensajeEsError = true) }
            }
        }
    }

    fun cerrarPartida(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.cerrarPartida()
            mostrarMensaje("Lote marcado como finalizado", esError = false)
            onDone()
        }
    }

    /**
     * Exporta el lote completo como un archivo .davi y abre el menú de compartir.
     * Al abrir ese archivo (WhatsApp, Archivos, etc.) la app lo reconoce y restaura
     * el lote entero automáticamente.
     */
    fun exportarLote(context: Context) {
        _ui.update { it.copy(procesando = true) }
        viewModelScope.launch {
            try {
                val file = exportService.exportarLote(context)
                exportService.shareFile(context, file)
                mostrarMensaje("Lote exportado", esError = false)
            } catch (e: Exception) {
                mostrarMensaje("Error al exportar: ${e.message}", esError = true)
            } finally {
                _ui.update { it.copy(procesando = false) }
            }
        }
    }

    fun limpiarMensaje() {
        _ui.update { it.copy(mensaje = "", mensajeEsError = false) }
    }

    private fun mostrarMensaje(msg: String, esError: Boolean) {
        _ui.update { it.copy(mensaje = msg, mensajeEsError = esError) }
    }
}
