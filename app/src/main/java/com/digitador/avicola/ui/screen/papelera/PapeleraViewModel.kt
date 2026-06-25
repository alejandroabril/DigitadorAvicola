package com.digitador.avicola.ui.screen.papelera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitador.avicola.data.repository.DigitadorRepository
import com.digitador.avicola.domain.PapeleraItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PapeleraUiState(
    val items: List<PapeleraItem> = emptyList(),
    val loading: Boolean = true
)

@HiltViewModel
class PapeleraViewModel @Inject constructor(
    private val repo: DigitadorRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(PapeleraUiState())
    val ui = _ui.asStateFlow()

    fun cargar() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            try {
                // Primero purga lo vencido, luego lista lo que queda.
                repo.purgarVencidas()
                val items = repo.getPapeleraSummaries()
                _ui.update { it.copy(items = items, loading = false) }
            } catch (e: Exception) {
                e.printStackTrace()
                _ui.update { it.copy(loading = false) }
            }
        }
    }

    fun restaurar(id: Long) {
        viewModelScope.launch {
            repo.restaurarDePapelera(id)
            cargar()
        }
    }

    fun borrarDefinitivo(id: Long) {
        viewModelScope.launch {
            repo.borrarFisicamente(id)
            cargar()
        }
    }
}
