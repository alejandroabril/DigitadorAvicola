package com.digitador.avicola.ui.screen.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitador.avicola.data.db.entity.BorradorLoteEntity
import com.digitador.avicola.data.repository.DigitadorRepository
import com.digitador.avicola.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Un lote con su edad inicial (días). Varios lotes por partida, 1 edad por lote. */
data class LoteEntry(
    val nombre: String = "",
    val edad: String = "0"
)

data class SetupUiState(
    val partidaId: Long = 0L,
    val numero: String = "",
    val lote: String = "",          // derivado: nombres unidos por coma (compat/BD)
    val edad: String = "",          // derivado: edades unidas por coma (compat/BD)
    val lotes: List<LoteEntry> = emptyList(), // fuente de verdad en la UI
    val fechaInicio: String = "",
    val usarGuia: Boolean = true,
    val numGaleras: Int = 1,
    val numTratamientos: Int = 4,
    val galeras: List<GaleraSetup> = emptyList(),
    val poolParcelas: Map<String, ParcelaSetup> = emptyMap(),
    val error: String = "",
    val saving: Boolean = false,
    val editMode: Boolean = false,
    /** true cuando estamos completando un lote pendiente (sin semanas aún). */
    val completingPending: Boolean = false,
    /** Paso del asistente al reabrir (1·Identif · 2·Recepción · 3·Revisión). */
    val initialStep: Int = 1,
    val parcelsPerTratamiento: Map<String, Int> = emptyMap(),
    val borradores: List<BorradorLoteEntity> = emptyList(),
    val usesManualDistribution: Boolean = true,
    val pendingDistribution: Map<String, Map<String, List<String>>>? = null
)

data class GaleraSetup(
    val id: String,
    val nombre: String,
    val corrales: List<CorralSetup> = emptyList()
)

data class CorralSetup(
    val id: String,
    val parcelas: List<ParcelaSetup> = emptyList()
)

data class ParcelaSetup(
    val id: String,
    val inicio: String = "",
    val pesoCaja: String = ""
) {
    val avesNum: Int get() = inicio.toIntOrNull() ?: 0
    val pesoNum: Double get() = pesoCaja.toDoubleOrNull() ?: 0.0
    val pesoPromedio: Double get() =
        if (avesNum > 0) pesoNum / avesNum else 0.0

    /** Estado de la parcela en el Paso 2 (Recepción). */
    val estado: EstadoRecepcion get() = when {
        avesNum == 0 && pesoNum == 0.0 -> EstadoRecepcion.VACIA
        avesNum == 0 || pesoNum == 0.0 -> EstadoRecepcion.INCOMPLETA
        avesNum !in RANGO_AVES         -> EstadoRecepcion.FUERA_DE_RANGO
        pesoPromedio !in RANGO_PESO_AVE -> EstadoRecepcion.FUERA_DE_RANGO
        else                           -> EstadoRecepcion.OK
    }

    val isValid: Boolean get() = estado == EstadoRecepcion.OK
    val isStarted: Boolean get() = estado != EstadoRecepcion.VACIA

    companion object {
        /** Rangos de cordura para evitar errores de digitación. */
        val RANGO_AVES = 1..5000
        val RANGO_PESO_AVE = 20.0..100.0   // g/ave razonable en recepción
    }
}

/** Estado de captura para una parcela en el Paso 2. */
enum class EstadoRecepcion {
    VACIA,            // ningún campo digitado
    INCOMPLETA,       // solo uno de los dos campos
    FUERA_DE_RANGO,   // ambos digitados pero los valores no son razonables
    OK                // ambos digitados, valores dentro de rangos
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val repo: DigitadorRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(SetupUiState())
    val ui = _ui.asStateFlow()

    init {
        generarPoolInicial()
    }

    private fun generarPoolInicial() {
        _ui.update { it.copy(poolParcelas = buildPool(MIN_GALERAS_POOL, emptyMap())) }
    }

    /**
     * Construye el pool de parcelas para [numGaleras], preservando los valores ya
     * capturados en [existing]. Cada galera Gn usa dos líneas correlativas
     * (G1→A,B · G2→C,D · G3→E,F …), 20 parcelas por línea.
     */
    private fun buildPool(numGaleras: Int, existing: Map<String, ParcelaSetup>): Map<String, ParcelaSetup> {
        val pool = mutableMapOf<String, ParcelaSetup>()
        (1..numGaleras).forEach { gi ->
            val gId = "G$gi"
            val l1 = ('A' + (gi - 1) * 2)
            val l2 = ('A' + (gi - 1) * 2 + 1)
            listOf(l1, l2).forEach { line ->
                (1..20).forEach { pi ->
                    val id = "$gId$line${pi.toString().padStart(2, '0')}"
                    pool[id] = existing[id] ?: ParcelaSetup(id)
                }
            }
        }
        return pool
    }

    fun aplicarDistribucion() {
        val s = _ui.value
        val pool = s.poolParcelas
        
        val newGaleras = (1..s.numGaleras).map { gi ->
            val gId = "G$gi"
            
            // Recolectamos parcelas de esta galera del pool basándonos en el ID de galera (G1 o G2)
            val galeraParcels = pool.values.filter { it.id.startsWith(gId) }.sortedBy { it.id }
            
            val nTratamientos = s.numTratamientos
            val pPerCorral = (galeraParcels.size / (if(nTratamientos > 0) nTratamientos else 1)).coerceAtLeast(1)
            
            val corrales = (1..nTratamientos).map { ki ->
                val start = (ki - 1) * pPerCorral
                val end = if (ki == nTratamientos) galeraParcels.size else (ki * pPerCorral).coerceAtMost(galeraParcels.size)
                val myParcels = if (start < galeraParcels.size) galeraParcels.subList(start, end) else emptyList()
                
                // Usamos el índice local 'ki' para que los nombres T1, T2... se repitan en cada galera
                // resultando en un conteo global unificado.
                val corralName = "T$ki"
                
                CorralSetup(id = "$gId-$corralName", parcelas = myParcels)
            }
            
            GaleraSetup(id = gId, nombre = "Galera $gi", corrales = corrales)
        }

        // Calcular parcelas por tratamiento para info inferior
        val stats = mutableMapOf<String, Int>()
        newGaleras.forEach { g ->
            g.corrales.forEach { c ->
                val label = c.id.split("-").last()
                stats[label] = (stats[label] ?: 0) + c.parcelas.size
            }
        }

        _ui.update { it.copy(galeras = newGaleras, parcelsPerTratamiento = stats) }
    }

    fun setNumGaleras(n: Int) {
        _ui.update {
            // Garantiza que el pool tenga parcelas para todas las galeras seleccionadas,
            // conservando lo ya capturado.
            val coverage = maxOf(n, MIN_GALERAS_POOL)
            it.copy(numGaleras = n, poolParcelas = buildPool(coverage, it.poolParcelas))
        }
    }

    fun setNumTratamientos(v: String) {
        val n = v.toIntOrNull() ?: 0
        if (n > 0) {
            _ui.update { it.copy(numTratamientos = n.coerceIn(1, 40), usesManualDistribution = true) }
        } else {
            _ui.update { it.copy(numTratamientos = 0) }
        }
    }

    fun importDistribucionJSON(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (json == null) {
                    _ui.update { it.copy(error = "No se pudo leer el archivo") }
                    return@launch
                }
                importDistribucionFromJson(json)
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Error al procesar JSON: ${e.message}") }
            }
        }
    }

    /** Importa una distribución desde un JSON ya leído (usado por ImportBus). */
    fun importDistribucionFromJson(json: String) {
        viewModelScope.launch {
            try {
                val gson = com.google.gson.Gson()
                val jsonElement = com.google.gson.JsonParser.parseString(json)

                val distributionMap: Map<String, Map<String, List<String>>> = if (jsonElement.isJsonObject && jsonElement.asJsonObject.has("galeras")) {
                    val fullState = gson.fromJson(json, SetupUiState::class.java)
                    fullState.galeras.associate { g ->
                        g.id to g.corrales.associate { c ->
                            c.id.split("-").last() to c.parcelas.map { it.id }
                        }
                    }
                } else {
                    val type = object : com.google.gson.reflect.TypeToken<Map<String, Map<String, List<String>>>>() {}.type
                    gson.fromJson(json, type)
                }

                if (distributionMap.isEmpty()) {
                    _ui.update { it.copy(error = "El archivo no contiene una estructura válida") }
                    return@launch
                }

                _ui.update { it.copy(pendingDistribution = distributionMap, error = "") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Error al procesar: ${e.message}") }
            }
        }
    }

    fun confirmDistribution() {
        val distributionMap = _ui.value.pendingDistribution ?: return
        val pool = _ui.value.poolParcelas
        
        val newGaleras = distributionMap.map { (gId, corralesMap) ->
            val corrales = corralesMap.map { (tLabel, pIds) ->
                val myParcels = pIds.mapNotNull { pool[it] }
                CorralSetup(id = "$gId-$tLabel", parcelas = myParcels)
            }
            GaleraSetup(id = gId, nombre = "Galera ${if(gId.length > 1) gId.drop(1) else gId}", corrales = corrales)
        }

        val stats = mutableMapOf<String, Int>()
        newGaleras.forEach { g ->
            g.corrales.forEach { c ->
                val label = c.id.split("-").last()
                stats[label] = (stats[label] ?: 0) + c.parcelas.size
            }
        }

        _ui.update { it.copy(
            galeras = newGaleras, 
            usesManualDistribution = false,
            numGaleras = newGaleras.size,
            parcelsPerTratamiento = stats,
            pendingDistribution = null,
            error = "Distribución aplicada correctamente"
        ) }
        
        // Limpiar el mensaje después de 2 segundos
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            if (_ui.value.error == "Distribución aplicada correctamente") {
                _ui.update { it.copy(error = "") }
            }
        }
    }

    fun cancelDistribution() {
        _ui.update { it.copy(pendingDistribution = null) }
    }

    fun updatePoolParcela(pId: String, aves: String? = null, peso: String? = null) {
        _ui.update { s ->
            val pool = s.poolParcelas.toMutableMap()
            pool[pId]?.let { p ->
                pool[pId] = p.copy(inicio = aves ?: p.inicio, pesoCaja = peso ?: p.pesoCaja)
            }
            s.copy(poolParcelas = pool)
        }
    }

    fun descargarEjemploJSON(context: android.content.Context) {
        val ejemplo = mapOf(
            "G1" to mapOf(
                "T1" to listOf("A01", "A02", "B01"),
                "T2" to listOf("A03", "B02", "B03")
            )
        )
        val json = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(ejemplo)
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
                val file = File(dir, "ejemplo_distribucion.davi")
                file.writeText(json)
                
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, 
                    "${context.packageName}.provider", 
                    file
                )
                
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = android.content.Intent.createChooser(intent, "Descargar ejemplo JSON")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                e.printStackTrace()
                _ui.update { it.copy(error = "Error al generar ejemplo: ${e.message}") }
            }
        }
    }

    fun cargarParaEdicion() {
        viewModelScope.launch {
            // Carga read-only: no muta el appState global (no recompone otras
            // pantallas) y salta datos/refs cuando el lote aún no tiene semanas.
            val state = repo.cargarEstadoParaSetup()
            val p = state.partida ?: return@launch

            // TODO el cómputo (bucles sobre galeras→corrales→parcelas) corre en
            // Dispatchers.Default para no bloquear el main thread mientras la
            // pantalla anima su entrada. Sólo el _ui.update final toca el StateFlow.
            val nuevoEstado = withContext(Dispatchers.Default) {
                // Pool a partir de la BD (preserva ids reales — p.ej. G1A01 del .davi).
                val pool = mutableMapOf<String, ParcelaSetup>()
                p.galeras.forEach { g ->
                    g.corrales.forEach { k ->
                        k.parcelas.forEach { par ->
                            pool[par.id] = ParcelaSetup(
                                id = par.id,
                                inicio = if (par.inicio == 0) "" else par.inicio.toString(),
                                pesoCaja = if (par.pesoInicio == 0.0) "" else par.pesoInicio.toInt().toString()
                            )
                        }
                    }
                }

                // Reconstruir galeras desde la BD (NO usar aplicarDistribucion:
                // rompería la distribución personalizada que vino del .davi).
                val galeras = p.galeras.map { g ->
                    GaleraSetup(
                        id = g.id,
                        nombre = g.nombre,
                        corrales = g.corrales.map { c ->
                            CorralSetup(
                                id = c.id,
                                parcelas = c.parcelas.map { par -> pool[par.id] ?: ParcelaSetup(par.id) }
                            )
                        }
                    )
                }

                // Resumen "parcelas por tratamiento".
                val stats = mutableMapOf<String, Int>()
                galeras.forEach { g ->
                    g.corrales.forEach { c ->
                        val label = c.id.split("-").last()
                        stats[label] = (stats[label] ?: 0) + c.parcelas.size
                    }
                }

                // Pendiente: Nº/fecha vacíos o sin semanas → crear semana 1 al guardar.
                val esPendiente = p.numero.isBlank() || p.fechaInicio.isBlank() || state.semanas.isEmpty()

                // Paso de arranque para que el usuario retome donde lo dejó.
                val identificacionLista = p.numero.isNotBlank() && p.fechaInicio.isNotBlank()
                val recepcionLista = p.galeras.isNotEmpty() &&
                    p.galeras.all { g -> g.corrales.all { c -> c.parcelas.all { it.inicio > 0 } } }
                val pasoInicial = when {
                    !identificacionLista    -> 1
                    !recepcionLista         -> 2
                    state.semanas.isEmpty() -> 3
                    else                    -> 1   // ya completo: editar identificación
                }

                _ui.value.copy(
                    partidaId = p.id,
                    numero = p.numero,
                    lote = p.lote,
                    edad = p.edad,
                    lotes = parseLotes(p.lote, p.edad),
                    fechaInicio = p.fechaInicio,
                    usarGuia = p.usarGuia,
                    editMode = true,
                    completingPending = esPendiente,
                    initialStep = pasoInicial,
                    numGaleras = p.galeras.size,
                    numTratamientos = p.galeras.firstOrNull()?.corrales?.size ?: 1,
                    poolParcelas = pool,
                    galeras = galeras,
                    parcelsPerTratamiento = stats,
                    usesManualDistribution = false
                )
            }

            _ui.value = nuevoEstado
        }
    }

    /** Auto-save Paso 1 → Paso 2: persiste solo identificación. */
    fun guardarIdentificacion() {
        val s = _ui.value
        if (s.partidaId == 0L) return
        viewModelScope.launch {
            repo.actualizarIdentificacion(
                partidaId   = s.partidaId,
                numero      = s.numero.trim(),
                lote        = s.lote.trim(),
                edad        = s.edad.trim(),
                fechaInicio = s.fechaInicio,
                usarGuia    = s.usarGuia
            )
        }
    }

    /**
     * Refresca [SetupUiState.galeras] tomando los valores actuales del pool. El Paso 2
     * edita [poolParcelas]; el Paso 3 (Revisión) lee de [galeras], así que hay que
     * sincronizar antes de pasar para que el resumen muestre lo recién digitado.
     */
    fun sincronizarGalerasConPool() {
        _ui.update { s ->
            val galeras = s.galeras.map { g ->
                g.copy(corrales = g.corrales.map { c ->
                    c.copy(parcelas = c.parcelas.map { p -> s.poolParcelas[p.id] ?: p })
                })
            }
            s.copy(galeras = galeras)
        }
    }

    /** Auto-save Paso 2 → Paso 3: persiste solo recepción (aves/peso por parcela). */
    fun guardarRecepcion() {
        val s = _ui.value
        if (s.partidaId == 0L) return
        val data = s.poolParcelas.mapValues { (_, p) ->
            (p.inicio.toIntOrNull() ?: 0) to (p.pesoCaja.toDoubleOrNull() ?: 0.0)
        }
        viewModelScope.launch {
            repo.actualizarRecepcion(s.partidaId, data)
        }
    }

    fun guardarProgresoTemporal(context: android.content.Context) {
        viewModelScope.launch {
            try {
                val json = com.google.gson.Gson().toJson(_ui.value)
                val file = File(context.filesDir, "temp_lot_progress.json")
                file.writeText(json)
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Error al guardar progreso: ${e.message}") }
            }
        }
    }

    fun cargarProgresoTemporal(context: android.content.Context) {
        viewModelScope.launch {
            try {
                val file = File(context.filesDir, "temp_lot_progress.json")
                if (file.exists()) {
                    val json = file.readText()
                    val savedState = com.google.gson.Gson().fromJson(json, SetupUiState::class.java)
                    _ui.update { savedState.copy(error = "", saving = false, lotes = parseLotes(savedState.lote, savedState.edad)) }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Error al cargar progreso: ${e.message}") }
            }
        }
    }

    fun guardarBorrador(nombre: String, id: Long = 0L) {
        viewModelScope.launch {
            try {
                val json = com.google.gson.Gson().toJson(_ui.value)
                if (id == 0L) {
                    repo.saveBorrador(nombre, json)
                } else {
                    repo.saveBorrador(nombre, json, id)
                }
                refreshBorradores()
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Error al guardar borrador: ${e.message}") }
            }
        }
    }

    fun refreshBorradores() {
        viewModelScope.launch {
            val list = repo.getBorradores()
            _ui.update { it.copy(borradores = list) }
        }
    }

    fun cargarBorrador(b: BorradorLoteEntity) {
        try {
            val savedState = com.google.gson.Gson().fromJson(b.jsonData, SetupUiState::class.java)
            _ui.update { savedState.copy(error = "", saving = false, lotes = parseLotes(savedState.lote, savedState.edad)) }
        } catch (e: Exception) {
            _ui.update { it.copy(error = "Error al cargar borrador: ${e.message}") }
        }
    }

    fun borrarBorrador(id: Long) {
        viewModelScope.launch {
            repo.deleteBorrador(id)
            refreshBorradores()
        }
    }

    /**
     * Extrae SOLO la distribución de parcelas (galera → tratamiento → parcelas) de un
     * estado guardado, sin número de partida, edad, fecha ni nombres de lote.
     * Formato: { "G1": { "T1": ["G1A01", ...], "T2": [...] }, ... }
     */
    private fun distribucionJson(state: SetupUiState): String {
        val dist: Map<String, Map<String, List<String>>> = state.galeras.associate { g ->
            g.id to g.corrales.associate { c ->
                c.id.split("-").last() to c.parcelas.map { it.id }
            }
        }
        return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(dist)
    }

    fun compartirBorrador(context: android.content.Context, b: BorradorLoteEntity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "exports").also { it.mkdirs() }

                // Solo la distribución de parcelas (sin número, lote, edad ni fecha)
                val savedState = com.google.gson.Gson().fromJson(b.jsonData, SetupUiState::class.java)

                val timestamp = java.time.Instant.ofEpochMilli(b.fechaGuardado)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("ddMMyy_HHmm"))

                val fileName = "Distribucion_$timestamp.davi"
                val file = File(dir, fileName)
                file.writeText(distribucionJson(savedState))

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
                
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = android.content.Intent.createChooser(intent, "Compartir Distribución")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                
                context.startActivity(chooser)
            } catch (e: Exception) {
                e.printStackTrace()
                _ui.update { it.copy(error = "Error al compartir: ${e.message}") }
            }
        }
    }

    fun descargarBorrador(context: android.content.Context, b: BorradorLoteEntity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val savedState = com.google.gson.Gson().fromJson(b.jsonData, SetupUiState::class.java)
                val distJson = distribucionJson(savedState)
                val timestamp = java.time.Instant.ofEpochMilli(b.fechaGuardado)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("ddMMyy_HHmm"))

                val fileName = "Distribucion_$timestamp.davi"

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    
                    uri?.let {
                        resolver.openOutputStream(it)?.use { outputStream ->
                            outputStream.write(distJson.toByteArray())
                        }
                        _ui.update { s -> s.copy(error = "Archivo guardado en Descargas: $fileName") }
                    } ?: throw Exception("No se pudo crear el archivo en Descargas")
                } else {
                    // Para versiones antiguas de Android
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val file = File(downloadsDir, fileName)
                    file.writeText(distJson)
                    _ui.update { s -> s.copy(error = "Archivo guardado en Descargas: $fileName") }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _ui.update { it.copy(error = "Error al descargar: ${e.message}") }
            }
        }
    }

    fun setNumero(v: String) {
        val limited = v.filter { it.isDigit() }.take(4)
        _ui.update { it.copy(numero = limited, error = "") }
    }
    
    fun setLote(v: String) { _ui.update { it.copy(lote = v) } }
    fun setEdad(v: String) { _ui.update { it.copy(edad = v) } }
    fun setFechaInicio(v: String){ _ui.update { it.copy(fechaInicio = v) } }

    // ── Lotes (chips con edad) ──────────────────────────────────
    private fun List<LoteEntry>.applyTo(s: SetupUiState): SetupUiState = s.copy(
        lotes = this,
        lote  = joinToString(", ") { it.nombre },
        edad  = joinToString(", ") { it.edad.ifBlank { "0" } }
    )

    fun addLote(nombre: String) {
        val limpio = nombre.trim()
        if (limpio.isBlank()) return
        _ui.update { s -> (s.lotes + LoteEntry(limpio, "0")).applyTo(s) }
    }

    fun removeLote(index: Int) {
        _ui.update { s ->
            s.lotes.toMutableList().also { if (index in it.indices) it.removeAt(index) }.applyTo(s)
        }
    }

    fun setLoteEdad(index: Int, edad: String) {
        val e = edad.filter { it.isDigit() }.take(3).trimStart('0').ifBlank { "0" }
        _ui.update { s ->
            s.lotes.toMutableList().also {
                if (index in it.indices) it[index] = it[index].copy(edad = e)
            }.applyTo(s)
        }
    }

    fun setLoteNombre(index: Int, nombre: String) {
        _ui.update { s ->
            s.lotes.toMutableList().also {
                if (index in it.indices) it[index] = it[index].copy(nombre = nombre.trim())
            }.applyTo(s)
        }
    }

    /** Reconstruye la lista de lotes desde los strings coma-separados (BD/borrador). */
    private fun parseLotes(loteStr: String, edadStr: String): List<LoteEntry> {
        val nombres = loteStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val edades  = edadStr.split(",").map { it.trim() }
        if (nombres.isEmpty()) return emptyList()
        return nombres.mapIndexed { i, n ->
            LoteEntry(n, edades.getOrNull(i)?.ifBlank { "0" } ?: "0")
        }
    }

    fun guardar(onSuccess: (Long) -> Unit) {
        val s = _ui.value
        if (s.numero.length < 4 || s.fechaInicio.isBlank()) {
            _ui.update { it.copy(error = "El Nº de partida debe ser de 4 dígitos y la fecha es obligatoria.") }
            return
        }

        _ui.update { it.copy(saving = true, error = "") }
        viewModelScope.launch {
            // Unicidad del número de partida (excluye la propia al completar/editar).
            if (repo.existeOtraPartidaConNumero(s.numero.trim(), s.partidaId)) {
                _ui.update { it.copy(saving = false, error = "Ya existe una partida con el Nº ${s.numero.trim()}. Usá otro número.") }
                return@launch
            }
            val partida = Partida(
                id = s.partidaId,
                numero = s.numero.trim(),
                lote = s.lote.trim(),
                edad = s.edad.trim(),
                fechaInicio = s.fechaInicio,
                usarGuia = s.usarGuia,
                galeras = s.galeras.map { g ->
                    Galera(
                        id = g.id,
                        nombre = g.nombre,
                        corrales = g.corrales.map { k ->
                            Corral(
                                id = k.id,
                                galeraId = g.id,
                                parcelas = k.parcelas.map { p ->
                                    Parcela(
                                        id = p.id,
                                        corralId = k.id,
                                        inicio = p.inicio.toIntOrNull() ?: 0,
                                        pesoInicio = p.pesoCaja.toDoubleOrNull() ?: 0.0
                                    )
                                }
                            )
                        }
                    )
                }
            )
            val newId = repo.guardarPartida(partida)

            // Crear Semana 1 cuando es un lote NUEVO o un PENDIENTE recién completado.
            if (!s.editMode || s.completingPending) {
                repo.upsertSemana(Semana(
                    numero = 1,
                    fechaInicio = s.fechaInicio,
                    fechaFin = addDays(s.fechaInicio, 6),
                    refsActivas = listOf("BR1")
                ))
            }
            _ui.update { it.copy(saving = false) }
            onSuccess(newId)
        }
    }

    private fun addDays(dateStr: String, n: Int): String = DateUtils.addDays(dateStr, n)

    companion object {
        /** El pool siempre cubre al menos estas galeras (máximo que admite la UI). */
        private const val MIN_GALERAS_POOL = 2
    }
}
