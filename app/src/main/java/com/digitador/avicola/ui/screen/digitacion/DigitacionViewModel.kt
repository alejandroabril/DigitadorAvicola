package com.digitador.avicola.ui.screen.digitacion

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitador.avicola.data.repository.ConfigRepository
import com.digitador.avicola.data.repository.DigitadorRepository
import com.digitador.avicola.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class DigitacionCategory { MORTALIDAD, PESO, ALIMENTO }

data class DigitacionUiState(
    val semana: Semana? = null,
    val galera: Galera? = null,
    val parcels: List<Parcela> = emptyList(), 
    val datos: Map<String, Map<Int, DatoParcela>> = emptyMap(),
    val loading: Boolean = true,
    val activeCategory: DigitacionCategory = DigitacionCategory.MORTALIDAD,
    val availableGroups: List<String> = emptyList(),
    val activeGroup: String = "",
    val savingFields: Set<String> = emptySet(),
    val kpiVivas: Int = 0,
    val kpiPromedio: Double = 0.0,
    val kpiMortalidad: Int = 0,
    /** Agrupación activa: false = por línea (A/B…) · true = por tratamiento (K1, K2…). */
    val porTratamiento: Boolean = false,
    /** Referencias de alimento desechadas del cálculo de indicadores (por lote). */
    val refsExcluidas: Set<String> = emptySet(),
    /** uid del lote (para persistir preferencias como las refs excluidas). */
    val partidaUid: String = "",
    /** Lote cerrado O semana cerrada → digitación en solo lectura. */
    val finalizada: Boolean = false,
    /** Motivo del bloqueo, para el banner: "lote" o "semana". */
    val motivoBloqueo: String = ""
)

@Stable
@HiltViewModel
class DigitacionViewModel @Inject constructor(
    private val repo: DigitadorRepository,
    private val config: ConfigRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(DigitacionUiState())
    val ui: StateFlow<DigitacionUiState> = _ui.asStateFlow()

    private var kpiJob: Job? = null
    private var saveJob: Job? = null

    fun cargar(semNum: Int, galeraId: String, prefGroup: String? = null) {
        if (_ui.value.galera?.id == galeraId && _ui.value.semana?.numero == semNum && !ui.value.loading) return

        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val state = repo.appState.value.let {
                if (it.partida == null) repo.cargarEstado() else it 
            }
            val galera = state.partida?.galeras?.find { it.id == galeraId }
            val semana = state.getSemana(semNum)

            val porTrat = config.modoPorTratamiento.value
            val groups = gruposDe(galera, porTrat)
            val initialGroup = if (prefGroup != null && groups.contains(prefGroup)) prefGroup
                               else (groups.firstOrNull() ?: "")
            val filteredParcels = parcelasDe(galera, initialGroup, porTrat)

            _ui.update { it.copy(
                semana = semana,
                galera = galera,
                parcels = filteredParcels,
                datos = state.datosPorParcela,
                loading = false,
                availableGroups = groups,
                activeGroup = initialGroup,
                porTratamiento = porTrat,
                refsExcluidas = config.refsExcluidas(state.partida?.uid ?: "", semNum),
                partidaUid = state.partida?.uid ?: "",
                finalizada = (state.partida?.finalizada == true) || (semana?.cerrada == true),
                motivoBloqueo = when {
                    state.partida?.finalizada == true -> "lote"
                    semana?.cerrada == true -> "semana"
                    else -> ""
                }
            ) }
            recalcKpis()
        }
    }

    /** El PIN protege suspender y reactivar: ambas cambian qué entra en el análisis. */
    fun pinHabilitado(): Boolean = config.pinHabilitado.value
    fun verificarPin(p: String): Boolean = config.verificarPin(p)

    /**
     * Suspende o reactiva una jaula. Al volver del repositorio, `parcels` se refresca
     * desde el estado recargado para que la fila cambie de aspecto en el acto.
     */
    fun setParcelaSuspendida(parcelaId: String, suspendida: Boolean, motivo: String = "") {
        viewModelScope.launch {
            repo.setParcelaSuspendida(parcelaId, suspendida, motivo)
            val st = _ui.value
            val galera = repo.appState.value.partida?.galeras?.find { it.id == st.galera?.id }
            _ui.update { it.copy(
                galera = galera,
                parcels = parcelasDe(galera, st.activeGroup, st.porTratamiento),
                datos = repo.appState.value.datosPorParcela
            ) }
            recalcKpis()
        }
    }

    fun setCategory(cat: DigitacionCategory) {
        _ui.update { it.copy(activeCategory = cat) }
    }

    fun setGroup(group: String) {
        val filtered = parcelasDe(_ui.value.galera, group, _ui.value.porTratamiento)
        _ui.update { it.copy(activeGroup = group, parcels = filtered) }
        recalcKpis()
    }

    /** Letra de línea de una parcela (quita el prefijo "G" + galera): "G1A05" → "A". */
    private fun lineaDe(id: String): String {
        val letters = id.filter { it.isLetter() }
        return if (letters.length > 1 && letters.startsWith("G", ignoreCase = true)) letters.drop(1) else letters
    }

    /** Grupos disponibles según el modo: líneas (A/B…) o tratamientos (K1, K2… orden numérico). */
    private fun gruposDe(galera: Galera?, porTrat: Boolean): List<String> {
        galera ?: return emptyList()
        return if (porTrat) {
            galera.corrales.map { it.id.substringAfterLast("-") }
                .distinct()
                .sortedBy { lbl -> lbl.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        } else {
            galera.corrales.flatMap { it.parcelas }
                .map { lineaDe(it.id) }
                .distinct().sorted()
        }
    }

    /** Parcelas del grupo seleccionado según el modo (tratamiento = un corral; línea = filtro). */
    private fun parcelasDe(galera: Galera?, group: String, porTrat: Boolean): List<Parcela> {
        galera ?: return emptyList()
        return if (porTrat) {
            galera.corrales.firstOrNull { it.id.substringAfterLast("-") == group }
                ?.parcelas?.sortedBy { it.id } ?: emptyList()
        } else {
            galera.corrales.flatMap { it.parcelas }
                .filter { lineaDe(it.id) == group }
                .sortedBy { it.id }
        }
    }

    /** Recalcula los KPIs con un pequeño debounce: evita el cálculo O(parcelas×semanas)
     *  en cada pulsación de tecla; solo corre ~300 ms después de la última. */
    private fun scheduleKpis() {
        kpiJob?.cancel()
        kpiJob = viewModelScope.launch {
            delay(300)
            recalcKpis()
        }
    }

    private fun recalcKpis() {
        val state = _ui.value
        val semNum = state.semana?.numero ?: return
        val parcels = state.parcels
        
        var totVivas = 0
        var totMort = 0
        var spPeso = 0.0
        var pesoCount = 0

        parcels.forEach { p ->
            val datosByPar = state.datos[p.id] ?: emptyMap()
            val d = datosByPar[semNum]
            val saldoAnt = Calculadora.getSaldoAnterior(semNum, p.id, p, datosByPar)
            val mort = d?.mort?.sumOf { it ?: 0 } ?: 0
            
            totVivas += (saldoAnt - mort)
            totMort += mort
            d?.peso?.let { 
                spPeso += it
                pesoCount++
            }
        }

        _ui.update { it.copy(
            kpiVivas = totVivas,
            kpiMortalidad = totMort,
            kpiPromedio = if (pesoCount > 0) spPeso / pesoCount else 0.0
        ) }
    }

    fun updateMort(semNum: Int, pId: String, dayIdx: Int, value: String): Boolean {
        if (_ui.value.finalizada) return false   // lote cerrado: no se digita
        val currentDato = getDato(semNum, pId)
        val intValue = value.toIntOrNull()
        if (currentDato.mort[dayIdx] == intValue) return true

        if (intValue != null && intValue > 0) {
            val p = _ui.value.parcels.find { it.id == pId }
            if (p != null) {
                val datosByPar = _ui.value.datos[pId] ?: emptyMap()
                val saldoInicialSemana = Calculadora.getSaldoAnterior(semNum, pId, p, datosByPar)
                val mortOtrosDias = currentDato.mort.filterIndexed { i, _ -> i != dayIdx }.sumOf { it ?: 0 }
                val saldoDisponible = (saldoInicialSemana - mortOtrosDias).coerceAtLeast(0)
                if (intValue > saldoDisponible) return false
            }
        }

        val newMort = currentDato.mort.toMutableList()
        newMort[dayIdx] = intValue
        updateDatoLocal(semNum, pId, currentDato.copy(mort = newMort))
        scheduleKpis()
        scheduleSave()
        return true
    }

    fun updatePeso(semNum: Int, pId: String, value: String, saldo: Int) {
        if (_ui.value.finalizada) return
        val currentDato = getDato(semNum, pId)
        val total = value.toDoubleOrNull()
        val average = if (total != null && saldo > 0) total / saldo else null
        if (currentDato.peso == average) return
        updateDatoLocal(semNum, pId, currentDato.copy(peso = average))
        scheduleKpis()
        scheduleSave()
    }

    fun updateRef(semNum: Int, pId: String, tipo: String, field: String, value: String) {
        if (_ui.value.finalizada) return
        val currentDato = getDato(semNum, pId)
        val currentRef = currentDato.refs[tipo] ?: RefAlimento(tipo)
        var ing = currentRef.ingreso
        var sal = currentRef.saldoFin
        val doubleVal = value.replace(',', '.').toDoubleOrNull()
        if (field == "ingreso") { ing = doubleVal } else { sal = doubleVal }
        val newRefs = currentDato.refs.toMutableMap()
        newRefs[tipo] = RefAlimento(tipo, ing, sal)
        updateDatoLocal(semNum, pId, currentDato.copy(refs = newRefs))
        scheduleSave()
    }

    fun updateConsAjust(semNum: Int, pId: String, value: String) {
        if (_ui.value.finalizada) return
        val currentDato = getDato(semNum, pId)
        val doubleVal = value.replace(',', '.').toDoubleOrNull()
        if (currentDato.consAjust == doubleVal) return
        updateDatoLocal(semNum, pId, currentDato.copy(consAjust = doubleVal))
        scheduleSave()
    }

    /** Marca/desmarca una referencia como DESECHADA del cálculo SOLO en esta semana.
     *  La referencia sigue visible y se puede digitar; solo no cuenta en los indicadores. */
    fun toggleRefExcluida(tipo: String) {
        val uid = _ui.value.partidaUid
        val sem = _ui.value.semana?.numero ?: return
        if (uid.isBlank()) return
        val excluir = tipo !in _ui.value.refsExcluidas
        config.setRefExcluida(uid, sem, tipo, excluir)
        _ui.update { it.copy(refsExcluidas = config.refsExcluidas(uid, sem)) }
    }

    fun toggleRef(tipo: String) {
        if (_ui.value.finalizada) return
        val currentSem = _ui.value.semana ?: return
        val currentRefs = currentSem.refsActivas.toMutableList()
        if (currentRefs.contains(tipo)) {
            if (currentRefs.size > 1) currentRefs.remove(tipo)
        } else {
            currentRefs.add(tipo)
        }
        val newSem = currentSem.copy(refsActivas = currentRefs.sorted())
        _ui.update { it.copy(semana = newSem) }
        viewModelScope.launch { repo.upsertSemana(newSem) }
    }

    /** Guardado completo (al salir de la pantalla): persiste y refresca el estado global. */
    fun persist(onComplete: () -> Unit = {}) {
        if (_ui.value.finalizada) { onComplete(); return }  // lote cerrado: nada que guardar
        saveJob?.cancel()   // reemplaza cualquier autosave pendiente/en curso
        viewModelScope.launch {
            guardarSemanaActual()
            withContext(Dispatchers.IO) { repo.cargarEstado() }
            onComplete()
        }
    }

    /** Autosave con debounce: red de seguridad si el SO mata el proceso mientras se digita
     *  (tablet de campo con poca memoria, teclado abierto, salida por gesto, etc.). */
    private fun scheduleSave() {
        if (_ui.value.finalizada) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1500)
            guardarSemanaActual()
        }
    }

    /** Persiste los datos de la semana activa SIN recargar todo el lote: los save* ya
     *  parchean el estado en memoria, así que evitamos la recarga completa (cara). */
    private suspend fun guardarSemanaActual() {
        val state = _ui.value
        val semNum = state.semana?.numero ?: return
        withContext(Dispatchers.IO) {
            state.datos.forEach { (pId, datosPorSemana) ->
                val dato = datosPorSemana[semNum] ?: return@forEach
                repo.saveMortalidad(semNum, pId, dato.mort)
                repo.savePeso(semNum, pId, dato.peso)
                dato.refs.forEach { (tipo, ref) ->
                    repo.saveRefAlimento(semNum, pId, tipo, ref.ingreso, ref.saldoFin)
                }
                repo.saveConsAjust(semNum, pId, dato.consAjust)
            }
        }
    }

    /** Saldo de aves vivas de la parcela al cierre de la semana [semNum]
     *  (= aves al inicio de la semana − mortalidad de la semana). 0 si no se encuentra. */
    fun saldoAves(semNum: Int, pId: String): Int {
        val p = _ui.value.parcels.find { it.id == pId } ?: return 0
        val datosByPar = _ui.value.datos[pId] ?: emptyMap()
        val saldoAnt = Calculadora.getSaldoAnterior(semNum, pId, p, datosByPar)
        val mort = datosByPar[semNum]?.mort?.sumOf { it ?: 0 } ?: 0
        return (saldoAnt - mort).coerceAtLeast(0)
    }

    private fun getDato(semNum: Int, pId: String): DatoParcela {
        return _ui.value.datos[pId]?.get(semNum) ?: DatoParcela(semNum, pId)
    }

    private fun updateDatoLocal(semNum: Int, pId: String, newDato: DatoParcela) {
        _ui.update { state ->
            val parcelMap = state.datos[pId]?.toMutableMap() ?: mutableMapOf()
            parcelMap[semNum] = newDato
            val newDatos = state.datos.toMutableMap()
            newDatos[pId] = parcelMap
            state.copy(datos = newDatos)
        }
    }
}
