package com.digitador.avicola.ui.screen.resumen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitador.avicola.data.repository.AppState
import com.digitador.avicola.data.repository.DigitadorRepository
import com.digitador.avicola.data.repository.ExportService
import com.digitador.avicola.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ResumenUiState(
    val appState: AppState = AppState(),
    val semanaActual: Int = 1,
    val loading: Boolean = true
)

@HiltViewModel
class ResumenViewModel @Inject constructor(
    val repo: DigitadorRepository,
    val exportService: ExportService,
    private val config: com.digitador.avicola.data.repository.ConfigRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(ResumenUiState())
    val ui = _ui.asStateFlow()

    /** Mapa semana → referencias excluidas del cálculo, para las semanas 1..[semNum]. */
    fun refsExcluidasPorSemana(semNum: Int): Map<Int, Set<String>> {
        val uid = _ui.value.appState.partida?.uid ?: ""
        return (1..semNum).associateWith { config.refsExcluidas(uid, it) }
    }

    fun cargar(semNum: Int) {
        _ui.update { it.copy(semanaActual = semNum, loading = true) }
        viewModelScope.launch {
            try {
                val state = repo.cargarEstado()
                _ui.update { it.copy(appState = state, loading = false) }
            } catch (e: Exception) {
                e.printStackTrace()
                _ui.update { it.copy(loading = false) }   // nunca dejar el spinner colgado
            }
        }
    }

    fun getMetricasCorral(galera: Galera, corral: Corral, semNum: Int): MetricasCorral? {
        val st = _ui.value.appState
        val semana = st.getSemana(semNum) ?: return null
        return Calculadora.computeMetricasCorral(
            corral          = corral,
            semNum          = semNum,
            semana          = semana,
            todasSemanas    = st.semanas,
            datosPorParcela = st.datosPorParcela,
            refsExcluidasPorSemana = refsExcluidasPorSemana(semNum)
        )
    }
}
