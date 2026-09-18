package com.digitador.avicola.ui.screen.resumen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitador.avicola.data.repository.AppState
import com.digitador.avicola.data.repository.ConfigRepository
import com.digitador.avicola.data.repository.DigitadorRepository
import com.digitador.avicola.data.repository.ExportService
import com.digitador.avicola.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ResumenUiState(
    val appState: AppState = AppState(),
    val semanaActual: Int = 1,
    val loading: Boolean = true,
    /**
     * Métricas ya calculadas de cada tratamiento, por `Corral.id`. La pantalla solo
     * las lee: calcular durante la composición haría el trabajo de nuevo en cada
     * recomposición (y en el hilo de UI).
     */
    val metricasPorCorral: Map<String, MetricasCorral> = emptyMap(),
    val global: KpiGlobal = KpiGlobal()
)

@HiltViewModel
class ResumenViewModel @Inject constructor(
    val repo: DigitadorRepository,
    val exportService: ExportService,
    private val config: ConfigRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(ResumenUiState())
    val ui = _ui.asStateFlow()

    fun cargar(semNum: Int) {
        _ui.update { it.copy(semanaActual = semNum, loading = true) }
        viewModelScope.launch {
            try {
                val state = repo.cargarEstado()
                // Fuera del hilo de UI: recorre todas las jaulas de todas las semanas.
                val (metricas, global) = withContext(Dispatchers.Default) { analizar(state, semNum) }
                _ui.update {
                    it.copy(
                        appState = state,
                        metricasPorCorral = metricas,
                        global = global,
                        loading = false
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _ui.update { it.copy(loading = false) }   // nunca dejar el spinner colgado
            }
        }
    }

    /**
     * Calcula de una sola pasada las métricas de cada tratamiento y los KPIs del lote.
     * Antes el corral se calculaba dos veces —una para la tarjeta global y otra para su
     * fila— y las referencias excluidas se releían de preferencias por cada corral.
     */
    private fun analizar(state: AppState, semNum: Int): Pair<Map<String, MetricasCorral>, KpiGlobal> {
        val semana = state.getSemana(semNum) ?: return emptyMap<String, MetricasCorral>() to KpiGlobal()
        val uid = state.partida?.uid ?: ""
        // Una sola lectura de preferencias para todas las semanas y todos los corrales.
        val excluidas = (1..semNum).associateWith { config.refsExcluidas(uid, it) }

        val metricas = LinkedHashMap<String, MetricasCorral>()
        state.partida?.galeras?.forEach { galera ->
            galera.corrales.forEach { corral ->
                Calculadora.computeMetricasCorral(
                    corral = corral,
                    semNum = semNum,
                    semana = semana,
                    todasSemanas = state.semanas,
                    datosPorParcela = state.datosPorParcela,
                    refsExcluidasPorSemana = excluidas
                )?.let { metricas[corral.id] = it }
            }
        }
        return metricas to Calculadora.agregarKpiGlobal(metricas.values)
    }
}
