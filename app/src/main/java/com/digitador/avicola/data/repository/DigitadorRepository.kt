package com.digitador.avicola.data.repository

import androidx.room.withTransaction
import com.digitador.avicola.data.db.DigitadorDatabase
import com.digitador.avicola.data.db.dao.PartidaDao
import com.digitador.avicola.data.db.dao.SemanaDao
import com.digitador.avicola.data.db.entity.*
import com.digitador.avicola.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DigitadorRepository @Inject constructor(
    private val db: DigitadorDatabase,
    private val partidaDao: PartidaDao,
    private val semanaDao: SemanaDao
) {
    @Volatile private var currentPartidaId: Long? = null
    private val _appState = MutableStateFlow(AppState())
    val appState = _appState.asStateFlow()

    fun setCurrentPartida(id: Long) { 
        currentPartidaId = id 
    }

    suspend fun cargarEstado(partidaId: Long? = currentPartidaId): AppState = withContext(Dispatchers.IO) {
        val id = partidaId ?: partidaDao.getLatestPartida()?.id ?: return@withContext AppState()
        currentPartidaId = id

        val state = loadEstado(id)
        _appState.value = state
        state
    }

    /**
     * Carga el estado de una partida SIN mutar [currentPartidaId] ni [_appState].
     * Úsalo para inspeccionar partidas que no son la activa (p.ej. resúmenes del historial).
     */
    private suspend fun loadEstado(id: Long): AppState = withContext(Dispatchers.IO) {
        val partida = loadPartida(id)
        val semanas = semanaDao.getSemanasByPartida(id).map { it.toDomain() }
        // Camino rápido: sin semanas no hay datos ni refs que procesar.
        if (semanas.isEmpty()) {
            return@withContext AppState(partida = partida, semanas = emptyList(), datosPorParcela = emptyMap())
        }
        val allDatos = semanaDao.getAllDatosByPartida(id)
        val allRefs  = semanaDao.getAllRefsByPartida(id)

        AppState(
            partida = partida,
            semanas = semanas,
            datosPorParcela = processDatos(allDatos, allRefs)
        )
    }

    /**
     * Carga read-only para el asistente de edición/completado. NO muta [_appState]
     * (evita recomposiciones en otras pantallas) pero sí fija [currentPartidaId]
     * para que los auto-saves del wizard apunten a la partida correcta.
     */
    suspend fun cargarEstadoParaSetup(partidaId: Long? = currentPartidaId): AppState = withContext(Dispatchers.IO) {
        val id = partidaId ?: partidaDao.getLatestPartida()?.id ?: return@withContext AppState()
        currentPartidaId = id
        loadEstado(id)
    }

    private fun processDatos(
        allDatos: List<DatoParcelaEntity>,
        allRefs: List<RefAlimentoEntity>
    ): Map<String, Map<Int, DatoParcela>> {
        val refsByParcela = allRefs.groupBy { it.parcelaId }
            .mapValues { entry -> 
                entry.value.groupBy { it.semanaNumero }
                    .mapValues { semEntry ->
                        semMapToRefDomain(semEntry.value)
                    }
            }

        return allDatos.groupBy { it.parcelaId }
            .mapValues { entry ->
                val pId = entry.key
                entry.value.associateBy({ it.semanaNumero }) { d ->
                    DatoParcela(
                        semanaNumero = d.semanaNumero,
                        parcelaId    = d.parcelaId,
                        mort         = d.mort,
                        peso         = d.peso,
                        pesos        = d.pesos,
                        consAjust    = d.consAjust,
                        refs         = refsByParcela[pId]?.get(d.semanaNumero) ?: emptyMap()
                    )
                }
            }
    }

    private fun semMapToRefDomain(entities: List<RefAlimentoEntity>): Map<String, RefAlimento> {
        return entities.associate { r ->
            r.tipo to RefAlimento(r.tipo, r.ingreso, r.saldoFin)
        }
    }

    private suspend fun loadPartida(id: Long): Partida? {
        val pe = partidaDao.getPartidaById(id) ?: return null
        val galeras = partidaDao.getGalerasByPartida(id)
        val corralesByGalera = partidaDao.getCorralesByPartida(id).groupBy { it.galeraId }
        val parcelasByCorral = partidaDao.getParcelasByPartida(id).groupBy { it.corralId }

        return Partida(
            id = pe.id,
            numero = pe.numero,
            lote = pe.lote,
            edad = pe.edad,
            fechaInicio = pe.fechaInicio,
            usarGuia = pe.usarGuia,
            finalizada = pe.finalizada,
            uid = pe.uid,
            galeras = galeras.map { g ->
                Galera(
                    id = g.id,
                    nombre = g.nombre,
                    corrales = (corralesByGalera[g.id] ?: emptyList()).map { k ->
                        Corral(
                            id = k.id,
                            galeraId = k.galeraId,
                            parcelas = (parcelasByCorral[k.id] ?: emptyList()).map { p ->
                                Parcela(p.id, p.corralId, p.inicio, p.pesoInicio)
                            }.sortedBy { it.id }
                        )
                    }.sortedBy { it.id }
                )
            }
        )
    }

    suspend fun guardarPartida(partida: Partida): Long = withContext(Dispatchers.IO) {
        // Si la partida no trae uid (lote nuevo), generamos uno inmutable.
        val uid = partida.uid.ifBlank {
            partida.id.takeIf { it != 0L }?.let { partidaDao.getPartidaById(it)?.uid }?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
        }
        // Toda la estructura (partida + galeras + corrales + parcelas + borrados) se
        // escribe de forma ATÓMICA: si algo falla, no queda un lote a medio guardar.
        val id = db.withTransaction {
            val pid = partidaDao.upsertPartida(
                PartidaEntity(
                    id = if (partida.id == 0L) 0L else partida.id,
                    numero = partida.numero,
                    lote = partida.lote,
                    edad = partida.edad,
                    fechaInicio = partida.fechaInicio,
                    usarGuia = partida.usarGuia,
                    finalizada = partida.finalizada,
                    uid = uid
                )
            )

            partida.galeras.forEachIndexed { gi, g ->
                partidaDao.upsertGalera(GaleraEntity(g.id, pid, g.nombre, gi))
                g.corrales.forEachIndexed { ki, k ->
                    partidaDao.upsertCorral(CorralEntity(k.id, pid, g.id, ki))
                    k.parcelas.forEachIndexed { pi, p ->
                        partidaDao.upsertParcela(ParcelaEntity(p.id, pid, k.id, p.inicio, p.pesoInicio, pi))
                    }
                }
            }

            // Eliminar la estructura que ya no existe (al editar un lote). El borrado en
            // cascada de Room arrastra datos/refs de las parcelas removidas.
            val galeraIds  = partida.galeras.map { it.id }
            val corralIds  = partida.galeras.flatMap { g -> g.corrales.map { it.id } }
            val parcelaIds = partida.galeras.flatMap { g -> g.corrales.flatMap { c -> c.parcelas.map { it.id } } }

            if (galeraIds.isEmpty()) partidaDao.deleteGalerasByPartida(pid)
            else partidaDao.deleteGalerasNotIn(pid, galeraIds)

            if (corralIds.isEmpty()) partidaDao.deleteCorralesByPartida(pid)
            else partidaDao.deleteCorralesNotIn(pid, corralIds)

            if (parcelaIds.isEmpty()) partidaDao.deleteParcelasByPartida(pid)
            else partidaDao.deleteParcelasNotIn(pid, parcelaIds)

            pid
        }
        currentPartidaId = id
        cargarEstado(id)
        id
    }

    /**
     * Crea una partida en estado PENDIENTE: solo trae la estructura de parcelas
     * desde el JSON de distribución (galera → tratamiento → parcelas); identificación
     * (Nº, lote, edad, fecha) y datos de recepción quedan vacíos. El usuario completa
     * después desde el historial.
     *
     * @param distribucion mapa {galeraId → {tLabel → [parcelaIds]}}
     * @return id de la nueva partida pendiente
     */
    suspend fun crearPartidaPendiente(
        distribucion: Map<String, Map<String, List<String>>>
    ): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val partidaId = partidaDao.upsertPartida(
                PartidaEntity(
                    id = 0L,
                    numero = "",
                    lote = "",
                    edad = "",
                    fechaInicio = "",
                    usarGuia = true,
                    finalizada = false,
                    uid = java.util.UUID.randomUUID().toString()
                )
            )
            distribucion.entries.forEachIndexed { gi, (gId, tratamientos) ->
                val galeraNombre = "Galera " + (gId.removePrefix("G").ifBlank { gId })
                partidaDao.upsertGalera(GaleraEntity(gId, partidaId, galeraNombre, gi))
                tratamientos.entries.forEachIndexed { ki, (tLabel, parcelas) ->
                    val corralId = "$gId-$tLabel"
                    partidaDao.upsertCorral(CorralEntity(corralId, partidaId, gId, ki))
                    parcelas.forEachIndexed { pi, pId ->
                        partidaDao.upsertParcela(
                            ParcelaEntity(pId, partidaId, corralId, inicio = 0, pesoInicio = 0.0, orden = pi)
                        )
                    }
                }
            }
            partidaId
        }
    }

    suspend fun actualizarFechaInicioLote(fecha: String) {
        val id = currentPartidaId ?: return
        partidaDao.updateFechaInicio(id, fecha)
    }

    // ── Unicidad de lotes ────────────────────────────────────────

    /** True si ya existe un lote con este UID (mismo lote ya cargado). */
    suspend fun existePartidaConUid(uid: String): Boolean = withContext(Dispatchers.IO) {
        uid.isNotBlank() && partidaDao.countByUid(uid) > 0
    }

    /** True si OTRA partida (distinta de [excludeId]) ya usa este número. */
    suspend fun existeOtraPartidaConNumero(numero: String, excludeId: Long): Boolean = withContext(Dispatchers.IO) {
        partidaDao.countByNumeroExcept(numero, excludeId) > 0
    }

    /**
     * Genera un número de copia consecutivo a partir de un número base:
     * "3580" → "3580-C2", "3580-C3"… Si el número ya trae sufijo, usa la raíz.
     */
    suspend fun generarNumeroCopia(numeroBase: String): String = withContext(Dispatchers.IO) {
        val base = numeroBase.substringBefore("-C").ifBlank { "lote" }
        val existentes = partidaDao.getAllNumeros().toSet()
        var n = 2
        while ("$base-C$n" in existentes) n++
        "$base-C$n"
    }

    /**
     * Auto-save de Paso 1: persiste solo la identificación de la partida
     * (Nº, lote, edad, fecha) sin tocar parcelas/semanas.
     */
    suspend fun actualizarIdentificacion(
        partidaId: Long,
        numero: String,
        lote: String,
        edad: String,
        fechaInicio: String,
        usarGuia: Boolean
    ) = withContext(Dispatchers.IO) {
        val pe = partidaDao.getPartidaById(partidaId) ?: return@withContext
        partidaDao.updatePartida(
            pe.copy(numero = numero, lote = lote, edad = edad, fechaInicio = fechaInicio, usarGuia = usarGuia)
        )
    }

    /**
     * Auto-save de Paso 2: persiste solo (inicio, pesoInicio) de cada parcela.
     * [recepcion] mapa parcelaId → (aves, pesoTotalCaja).
     */
    suspend fun actualizarRecepcion(
        partidaId: Long,
        recepcion: Map<String, Pair<Int, Double>>
    ) = withContext(Dispatchers.IO) {
        recepcion.forEach { (pid, data) ->
            partidaDao.updateParcelaInicio(partidaId, pid, data.first, data.second)
        }
    }

    suspend fun cerrarPartida() {
        val id = currentPartidaId ?: return
        val pe = partidaDao.getPartidaById(id) ?: return
        partidaDao.updatePartida(pe.copy(finalizada = true))
    }

    suspend fun resetearTodo() {
        val id = currentPartidaId ?: return
        borrarFisicamente(id)
    }

    // ── Papelera (soft delete con retención de 15 días) ──────────

    /** Mueve un lote a la papelera (no lo borra). */
    suspend fun moverAPapelera(id: Long) = withContext(Dispatchers.IO) {
        partidaDao.softDelete(id, System.currentTimeMillis())
        if (currentPartidaId == id) currentPartidaId = null
    }

    /** Restaura un lote de la papelera. */
    suspend fun restaurarDePapelera(id: Long) = withContext(Dispatchers.IO) {
        partidaDao.restore(id)
    }

    /** Borra DEFINITIVAMENTE un lote (estructura + semanas + datos + refs). */
    suspend fun borrarFisicamente(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            semanaDao.deleteAllRefsByPartida(id)
            semanaDao.deleteAllDatosByPartida(id)
            semanaDao.deleteSemanasByPartida(id)
            partidaDao.deletePartidaById(id)
        }
        if (currentPartidaId == id) currentPartidaId = null
    }

    /** Purga definitiva de los lotes cuya retención (15 días) ya venció. */
    suspend fun purgarVencidas(diasRetencion: Int = DIAS_RETENCION) = withContext(Dispatchers.IO) {
        val limite = System.currentTimeMillis() - diasRetencion * MS_POR_DIA
        partidaDao.getExpiradasIds(limite).forEach { borrarFisicamente(it) }
    }

    /** Lista de lotes en la papelera con días restantes antes de la purga. */
    suspend fun getPapeleraSummaries(diasRetencion: Int = DIAS_RETENCION): List<PapeleraItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        partidaDao.getPapeleraSync().map { pe ->
            val transcurridos = ((now - pe.eliminadaEn) / MS_POR_DIA).toInt()
            PapeleraItem(
                id = pe.id,
                numero = pe.numero,
                lote = pe.lote,
                eliminadaEn = pe.eliminadaEn,
                diasRestantes = (diasRetencion - transcurridos).coerceAtLeast(0)
            )
        }
    }

    suspend fun upsertSemana(semana: Semana) = withContext(Dispatchers.IO) {
        val id = currentPartidaId ?: return@withContext
        semanaDao.upsertSemana(
            SemanaEntity(
                partidaId = id,
                numero = semana.numero,
                fechaInicio = semana.fechaInicio,
                fechaFin = semana.fechaFin,
                refsActivas = semana.refsActivas,
                cerrada = semana.cerrada
            )
        )
        // Parche en memoria en vez de recargar todo el lote: solo cambió una semana.
        val cur = _appState.value
        if (cur.partida == null) {
            cargarEstado(id) // estado frío: carga completa
        } else {
            val nuevas = cur.semanas.filter { it.numero != semana.numero } + semana
            _appState.value = cur.copy(semanas = nuevas.sortedBy { it.numero })
        }
    }

    suspend fun borrarSemana(numero: Int) = withContext(Dispatchers.IO) {
        val id = currentPartidaId ?: return@withContext
        semanaDao.deleteRefsBySemana(id, numero)
        semanaDao.deleteDatosBySemana(id, numero)
        semanaDao.deleteSemana(id, numero)
        cargarEstado(id)
    }

    suspend fun getSemana(numero: Int): Semana? {
        val id = currentPartidaId ?: return null
        return semanaDao.getSemana(id, numero)?.toDomain()
    }

    /** Marca una semana como terminada/cerrada (digitación en solo lectura). */
    suspend fun cerrarSemana(numero: Int) = withContext(Dispatchers.IO) {
        val sem = getSemana(numero) ?: return@withContext
        upsertSemana(sem.copy(cerrada = true))
    }

    /** Reabre una semana cerrada (vuelve a ser editable). */
    suspend fun reabrirSemana(numero: Int) = withContext(Dispatchers.IO) {
        val sem = getSemana(numero) ?: return@withContext
        upsertSemana(sem.copy(cerrada = false))
    }

    suspend fun getPartidaSummaries(): List<PartidaSummary> = withContext(Dispatchers.IO) {
        val entities = partidaDao.getActivasSync()   // solo lotes no eliminados
        if (entities.isEmpty()) return@withContext emptyList()

        // ── PARALELO + camino rápido para pendientes ───────────────
        // Pendientes (sin semanas) usan agregados SQL baratos en vez de
        // cargar todas las galeras/corrales/parcelas/datos del estado.
        // Activas siguen el camino completo (necesitan calcProgreso).
        coroutineScope {
            entities.map { pe ->
                async { computeSummary(pe) }
            }.awaitAll()
        }
    }

    private suspend fun computeSummary(pe: PartidaEntity): PartidaSummary {
        val numSemanas = semanaDao.countSemanasByPartida(pe.id)

        // Camino rápido: lote sin semanas → sólo agregados, sin calcProgreso.
        if (numSemanas == 0) {
            val avesIniciales  = partidaDao.sumAvesByPartida(pe.id)
            val pesoIniciales  = partidaDao.sumPesoByPartida(pe.id)
            val parcelasTotal  = partidaDao.countParcelasByPartida(pe.id)
            val pasos = 1 +
                (if (pe.numero.isNotBlank() && pe.fechaInicio.isNotBlank()) 1 else 0) +
                (if (avesIniciales > 0) 1 else 0)
            return PartidaSummary(
                id = pe.id,
                numero = pe.numero,
                lote = pe.lote,
                avesIniciales = avesIniciales,
                avesActuales = avesIniciales,   // sin semanas → no hay mortalidad
                ultimaSemana = 1,
                progreso = 0,
                finalizada = pe.finalizada,
                pendiente = true,
                parcelasTotal = parcelasTotal,
                pesoInicialTotal = pesoIniciales,
                pasosCompletados = pasos
            )
        }

        // Camino completo: lote activo → carga estado para progreso real.
        val state = loadEstado(pe.id)
        val partida = state.partida ?: return PartidaSummary(
            pe.id, pe.numero, pe.lote, 0, 0, 1, 0, pe.finalizada, false
        )

        var avesIniciales = 0
        var pesoIniciales = 0.0
        var parcelasTotal = 0
        var totMortAcum = 0
        val maxSemNum = state.semanas.maxOfOrNull { it.numero } ?: 1

        partida.galeras.forEach { g ->
            g.corrales.forEach { c ->
                c.parcelas.forEach { p ->
                    avesIniciales += p.inicio
                    pesoIniciales += p.pesoInicio
                    parcelasTotal += 1
                    val datosByPar = state.datosPorParcela[p.id] ?: emptyMap()
                    for (sn in 1..maxSemNum) {
                        totMortAcum += datosByPar[sn]?.mort?.sumOf { it ?: 0 } ?: 0
                    }
                }
            }
        }

        val progreso = Calculadora.calcProgreso(maxSemNum, partida, state.getSemana(maxSemNum), state.datosPorParcela)

        return PartidaSummary(
            id = pe.id,
            numero = pe.numero,
            lote = pe.lote,
            avesIniciales = avesIniciales,
            avesActuales = (avesIniciales - totMortAcum).coerceAtLeast(0),
            ultimaSemana = maxSemNum,
            progreso = progreso.pct,
            finalizada = pe.finalizada,
            pendiente = false,
            parcelasTotal = parcelasTotal,
            pesoInicialTotal = pesoIniciales,
            pasosCompletados = 4
        )
    }

    suspend fun saveMortalidad(semNum: Int, parcelaId: String, mort: List<Int?>) = withContext(Dispatchers.IO) {
        val id = currentPartidaId ?: return@withContext
        val existing = semanaDao.getDato(id, semNum, parcelaId)
        semanaDao.upsertDato(
            DatoParcelaEntity(
                partidaId = id,
                semanaNumero = semNum,
                parcelaId = parcelaId,
                mort = mort,
                peso = existing?.peso,
                pesos = existing?.pesos ?: emptyList(),
                consAjust = existing?.consAjust
            )
        )
        patchDatoEnMemoria(semNum, parcelaId) { it.copy(mort = mort) }
    }

    suspend fun savePeso(semNum: Int, parcelaId: String, peso: Double?, pesos: List<Double> = emptyList()) = withContext(Dispatchers.IO) {
        val id = currentPartidaId ?: return@withContext
        val existing = semanaDao.getDato(id, semNum, parcelaId)
        semanaDao.upsertDato(
            DatoParcelaEntity(
                partidaId = id,
                semanaNumero = semNum,
                parcelaId = parcelaId,
                mort = existing?.mort ?: List(7) { null },
                peso = peso,
                pesos = pesos,
                consAjust = existing?.consAjust
            )
        )
        patchDatoEnMemoria(semNum, parcelaId) { it.copy(peso = peso, pesos = pesos) }
    }

    suspend fun saveConsAjust(semNum: Int, parcelaId: String, consAjust: Double?) = withContext(Dispatchers.IO) {
        val id = currentPartidaId ?: return@withContext
        val existing = semanaDao.getDato(id, semNum, parcelaId)
        semanaDao.upsertDato(
            DatoParcelaEntity(
                partidaId = id,
                semanaNumero = semNum,
                parcelaId = parcelaId,
                mort = existing?.mort ?: List(7) { null },
                peso = existing?.peso,
                pesos = existing?.pesos ?: emptyList(),
                consAjust = consAjust
            )
        )
        patchDatoEnMemoria(semNum, parcelaId) { it.copy(consAjust = consAjust) }
    }

    suspend fun saveRefAlimento(semNum: Int, parcelaId: String, tipo: String, ingreso: Double?, saldoFin: Double?) = withContext(Dispatchers.IO) {
        val id = currentPartidaId ?: return@withContext
        semanaDao.upsertRef(
            RefAlimentoEntity(id, semNum, parcelaId, tipo, ingreso, saldoFin)
        )
        patchDatoEnMemoria(semNum, parcelaId) { d ->
            d.copy(refs = d.refs.toMutableMap().apply { put(tipo, RefAlimento(tipo, ingreso, saldoFin)) })
        }
    }

    /**
     * Aplica un cambio puntual al [_appState] en memoria sin recargar toda la partida
     * desde la BD. Mantiene fresco el estado global (que observan otras pantallas como
     * Semana) de forma barata, evitando recargas completas por cada pulsación (OOM).
     */
    private fun patchDatoEnMemoria(
        semNum: Int,
        parcelaId: String,
        transform: (DatoParcela) -> DatoParcela
    ) {
        val cur = _appState.value
        if (cur.partida == null) return
        val parcelMap = cur.datosPorParcela[parcelaId]?.toMutableMap() ?: mutableMapOf()
        val old = parcelMap[semNum] ?: DatoParcela(semNum, parcelaId)
        parcelMap[semNum] = transform(old)
        val nuevos = cur.datosPorParcela.toMutableMap().apply { put(parcelaId, parcelMap) }
        _appState.value = cur.copy(datosPorParcela = nuevos)
    }

    // ── Borradores ──

    suspend fun saveBorrador(nombre: String, json: String, id: Long = 0L) {
        partidaDao.insertBorrador(BorradorLoteEntity(id = id, nombre = nombre, fechaGuardado = System.currentTimeMillis(), jsonData = json))
    }

    suspend fun getBorradores() = partidaDao.getBorradores()

    suspend fun deleteBorrador(id: Long) = partidaDao.deleteBorrador(id)

    private fun SemanaEntity.toDomain() = Semana(numero, fechaInicio, fechaFin, refsActivas, cerrada)

    companion object {
        /** Días que un lote permanece en la papelera antes de borrarse para siempre. */
        const val DIAS_RETENCION = 15
        private const val MS_POR_DIA = 24L * 60 * 60 * 1000
    }
}

data class AppState(
    val partida: Partida? = null,
    val semanas: List<Semana> = emptyList(),
    val datosPorParcela: Map<String, Map<Int, DatoParcela>> = emptyMap()
) {
    fun getSemana(n: Int) = semanas.find { it.numero == n }
}
