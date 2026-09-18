package com.digitador.avicola.ui.screen.semana

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitador.avicola.data.repository.AppState
import com.digitador.avicola.data.repository.ConfigRepository
import com.digitador.avicola.data.repository.DigitadorRepository
import com.digitador.avicola.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SemanaUiState(
    val appState: AppState = AppState(),
    val semanaActual: Int = 1,
    /**
     * Progreso de digitación de cada semana, ya calculado. La barra de navegación
     * pinta un porcentaje por semana; calcularlo durante la composición significaba
     * recorrer el lote entero una vez por cada semana, en cada recomposición.
     */
    val progresoPorSemana: Map<Int, ProgresoSemana> = emptyMap(),
    val loading: Boolean = true,
    val showResetDialog: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val error: String = ""
)

@HiltViewModel
class SemanaViewModel @Inject constructor(
    private val repo: DigitadorRepository,
    private val config: ConfigRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(SemanaUiState())
    val ui = _ui.asStateFlow()

    /** Config del PIN (para gatear el desbloqueo de semanas cerradas). */
    fun pinHabilitado(): Boolean = config.pinHabilitado.value
    fun verificarPin(p: String): Boolean = config.verificarPin(p)

    /** Modo de visualización: false = por línea · true = por tratamiento. Persistido. */
    val modoPorTratamiento: kotlinx.coroutines.flow.StateFlow<Boolean> = config.modoPorTratamiento
    fun setModoPorTratamiento(activo: Boolean) = config.setModoPorTratamiento(activo)

    /** Evita que la carga inicial se repita al recomponer (p.ej. al volver de digitar). */
    private var inicializado = false

    init {
        // Escuchar cambios globales del estado del lote
        viewModelScope.launch {
            repo.appState.collect { state ->
                _ui.update { it.copy(appState = state) }
                // El progreso recorre todas las jaulas de todas las semanas. Durante la
                // digitación el estado se parchea en cada tecla, así que se calcula fuera
                // del hilo de UI; si mientras tanto llegó un estado más nuevo, se descarta
                // (StateFlow ya conflaciona los intermedios).
                val progreso = withContext(Dispatchers.Default) { calcularProgreso(state) }
                _ui.update { if (it.appState === state) it.copy(progresoPorSemana = progreso) else it }
            }
        }
    }

    private fun calcularProgreso(state: AppState): Map<Int, ProgresoSemana> {
        val partida = state.partida ?: return emptyMap()
        return Calculadora.calcProgresoTodas(partida, state.semanas, state.datosPorParcela)
    }

    /**
     * Carga inicial: solo se ejecuta UNA vez por ViewModel. Al volver de la pantalla
     * de digitación, SemanaScreen se recompone y vuelve a llamar esto, pero ya no
     * debe reiniciar la semana visible (se conserva [SemanaUiState.semanaActual]).
     */
    fun cargarInicial(semanaNumero: Int) {
        if (inicializado) return
        inicializado = true
        viewModelScope.launch {
            // Reabrir en la semana donde se dejó: la última vista guardada (si es válida),
            // o si no, la última semana existente del lote.
            val state = repo.cargarEstado()
            _ui.update { it.copy(appState = state) }
            val maxSem = state.semanas.maxOfOrNull { it.numero } ?: 1
            val guardada = config.getUltimaSemana(state.partida?.uid ?: "")
            val objetivo = if (guardada in 1..maxSem) guardada else maxSem
            cargar(objetivo)
        }
    }

    fun cargar(semanaNumero: Int) {
        val currentState = _ui.value.appState
        // Si el lote YA está en memoria, cambiar de semana es solo un cambio de
        // estado: nada de spinner ni de recargar la BD (los datos de todas las
        // semanas ya están en appState).
        val yaCargado = currentState.partida != null

        _ui.update { it.copy(semanaActual = semanaNumero, loading = !yaCargado) }

        viewModelScope.launch {
            val state = if (yaCargado) currentState else repo.cargarEstado()
            // Recordar esta semana como la "última usada" del lote, para reabrir acá.
            state.partida?.uid?.let { config.setUltimaSemana(it, semanaNumero) }
            val sem = state.getSemana(semanaNumero)

            if (sem == null && state.partida != null) {
                // La semana no existe todavía → crearla (upsertSemana parchea el
                // appState en memoria, sin recargar todo el lote).
                val lastSem = state.semanas.filter { it.numero < semanaNumero }.maxByOrNull { it.numero }
                val nextInicio = if (lastSem?.fechaFin != null && lastSem.fechaFin.isNotBlank()) {
                    addDays(lastSem.fechaFin, 1)
                } else if (semanaNumero == 1) {
                    state.partida.fechaInicio
                } else ""

                val nextFin = if (nextInicio.isNotBlank()) addDays(nextInicio, 6) else ""
                val refsIniciales = lastSem?.refsActivas ?: listOf("BR1")

                repo.upsertSemana(
                    Semana(numero = semanaNumero, fechaInicio = nextInicio, fechaFin = nextFin, refsActivas = refsIniciales)
                )
            }

            if (!yaCargado) _ui.update { it.copy(loading = false) }
        }
    }

    fun agregarSemana() {
        viewModelScope.launch {
            val state = repo.cargarEstado()
            // Lote cerrado: no se permiten más semanas (queda inmutable).
            if (state.partida?.finalizada == true) {
                _ui.update { it.copy(error = "El lote está cerrado: no se pueden agregar semanas.") }
                return@launch
            }
            val semanas = state.semanas
            val lastSem = semanas.maxByOrNull { it.numero }

            // Condición: la última semana debe estar TERMINADA (cerrada) para crear la
            // siguiente. Terminar semana ya valida que pesos y alimento estén completos.
            if (lastSem != null && !lastSem.cerrada) {
                _ui.update { it.copy(error = "Terminá la semana ${lastSem.numero} antes de crear una nueva.") }
                return@launch
            }

            val next = (semanas.maxOfOrNull { it.numero } ?: 0) + 1

            // Las referencias activas de la nueva semana son las que dejaron SALDO (SAL > 0)
            // en la semana anterior: ese alimento continúa. Si ninguna dejó saldo, se
            // heredan las activas previas (o BR1 por defecto).
            val refsNuevaSemana = run {
                val partida = state.partida
                val refsConSaldo = if (lastSem != null && partida != null) {
                    lastSem.refsActivas.filter { tipo ->
                        partida.galeras.any { g ->
                            g.corrales.any { c ->
                                c.parcelas.any { p ->
                                    (state.datosPorParcela[p.id]?.get(lastSem.numero)
                                        ?.refs?.get(tipo)?.saldoFin ?: 0.0) > 0.0
                                }
                            }
                        }
                    }
                } else emptyList()
                if (refsConSaldo.isNotEmpty()) refsConSaldo
                else (lastSem?.refsActivas ?: listOf("BR1"))
            }

            // Calcular fechas basadas en la última semana
            val nextInicio = if (lastSem?.fechaFin != null && lastSem.fechaFin.isNotBlank()) {
                addDays(lastSem.fechaFin, 1)
            } else ""
            val nextFin = if (nextInicio.isNotBlank()) addDays(nextInicio, 6) else ""

            repo.upsertSemana(Semana(
                numero = next,
                fechaInicio = nextInicio,
                fechaFin = nextFin,
                refsActivas = refsNuevaSemana
            ))
            val finalState = repo.cargarEstado()
            _ui.update { it.copy(appState = finalState, semanaActual = next, error = "") }
        }
    }

    fun clearError() {
        _ui.update { it.copy(error = "") }
    }

    /** ¿La semana indicada está cerrada (terminada)? */
    fun semanaCerrada(numero: Int): Boolean =
        _ui.value.appState.getSemana(numero)?.cerrada == true

    /** Valida la completitud de una semana (pesos + alimento). */
    fun validarSemana(numero: Int): ValidacionSemana {
        val st = _ui.value.appState
        val p = st.partida ?: return ValidacionSemana(0, 0, 0)
        // Respetar el check "Excluir": esas referencias no se exigen al cerrar la semana.
        val excluidas = config.refsExcluidas(p.uid, numero)
        return Calculadora.validarSemana(numero, p, st.getSemana(numero), st.datosPorParcela, excluidas)
    }

    /** Cierra (termina) una semana: su digitación queda en solo lectura. */
    fun cerrarSemana(numero: Int) {
        viewModelScope.launch { repo.cerrarSemana(numero) }
    }

    /** Reabre una semana cerrada (tras validar el PIN en la UI). */
    fun reabrirSemana(numero: Int) {
        viewModelScope.launch { repo.reabrirSemana(numero) }
    }

    fun borrarSemanaActual(onDeleted: (Int) -> Unit) {
        val semNum = _ui.value.semanaActual
        viewModelScope.launch {
            repo.borrarSemana(semNum)
            val state = repo.cargarEstado()
            val nextSem = state.semanas.maxOfOrNull { it.numero } ?: 1
            _ui.update { it.copy(appState = state, showDeleteConfirm = false) }
            onDeleted(nextSem)
        }
    }

    fun showDeleteConfirm(show: Boolean) {
        _ui.update { it.copy(showDeleteConfirm = show) }
    }

    /**
     * Actualiza fechas de una semana. Si fechaFin queda vacío, se autocalcula
     * como fechaInicio + 6 días.
     */
    fun actualizarFechasSemana(semNum: Int, fechaInicio: String, fechaFin: String = "") {
        viewModelScope.launch {
            val sem = repo.getSemana(semNum) ?: Semana(numero = semNum)
            val finCalc = if (fechaFin.isBlank() && fechaInicio.isNotBlank()) {
                addDays(fechaInicio, 6)
            } else fechaFin
            repo.upsertSemana(sem.copy(fechaInicio = fechaInicio, fechaFin = finCalc))
            recargar()
        }
    }

    private fun addDays(dateStr: String, n: Int): String = DateUtils.addDays(dateStr, n)

    fun toggleRef(semNum: Int, tipo: String) {
        viewModelScope.launch {
            val sem = repo.getSemana(semNum) ?: Semana(numero = semNum)
            val refs = sem.refsActivas.toMutableList()
            if (tipo in refs) { if (refs.size > 1) refs.remove(tipo) }
            else refs.add(tipo)
            repo.upsertSemana(sem.copy(refsActivas = refs.sorted()))
            recargar()
        }
    }

    fun showResetDialog(show: Boolean) {
        _ui.update { it.copy(showResetDialog = show) }
    }

    private suspend fun recargar() {
        _ui.update { it.copy(appState = repo.cargarEstado()) }
    }

    fun getProgresoGalera(galera: Galera, semNum: Int): ProgresoSemana {
        val st     = _ui.value.appState
        val semana = st.getSemana(semNum)
        return Calculadora.calcProgresoGalera(galera, semana, semNum, st.datosPorParcela)
    }

}
