package com.digitador.avicola.ui.screen.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.FlutterDash
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitador.avicola.ui.components.*
import com.digitador.avicola.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onDone: (Long) -> Unit,
    onBack: () -> Unit,
    editMode: Boolean = false,
    /** >0 fuerza el paso de arranque (editar lote activo: 2 = Recepción). 0 = auto. */
    startStep: Int = 0,
    vm: SetupViewModel = hiltViewModel()
) {
    val editandoActivo = startStep > 0
    LaunchedEffect(editMode) {
        if (editMode) {
            // Diferimos la carga hasta que la transición de navegación (~250 ms)
            // termine, para que la animación de entrada no compita por el frame.
            // El procesamiento ya corre en Dispatchers.Default, así que esto solo
            // garantiza una entrada 100% fluida; el loader/crossfade cubre la espera.
            delay(220)
            vm.cargarParaEdicion()
        }
    }
    val ui by vm.ui.collectAsState()
    var currentStep by remember { mutableIntStateOf(1) }

    // Reanudación: en editMode debemos ESPERAR a que el ViewModel resuelva el paso
    // de arranque antes de renderizar ningún paso — si no, se ve el flash de Paso 1
    // y luego un salto al paso real.
    var stepSyncedFromVm by remember { mutableStateOf(!editMode) }
    LaunchedEffect(ui.partidaId, ui.initialStep) {
        if (!stepSyncedFromVm && ui.partidaId != 0L) {
            currentStep = if (startStep > 0) startStep else ui.initialStep
            stepSyncedFromVm = true
        }
    }

    val titulo = if (editandoActivo) "Editar lote" else "Completar lote"
    val nombrePaso = when(currentStep) {
        1 -> "Identificación"
        2 -> "Recepción"
        3 -> "Revisión"
        else -> ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(titulo, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            if (stepSyncedFromVm) "$nombrePaso • Paso $currentStep de 3" else "Cargando…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (currentStep > 1) currentStep-- else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AvicolaPrimary)
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            LinearProgressIndicator(
                progress = { if (stepSyncedFromVm) currentStep / 3f else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = AvicolaLight,
                trackColor = AvicolaPrimary.copy(alpha = 0.2f)
            )

            if (ui.error.isNotBlank()) {
                AlertBanner(ui.error, AlertType.ERROR)
            }

            // targetState: 0 = cargando (resolviendo paso inicial), 1..3 = pasos.
            val target = if (!stepSyncedFromVm) 0 else currentStep
            AnimatedContent(
                targetState = target,
                modifier = Modifier.weight(1f),
                label = "wizard_step",
                transitionSpec = {
                    val dur = 320
                    when {
                        // Carga inicial → primer paso: crossfade limpio (sin slide,
                        // la dirección no tiene semántica aquí).
                        initialState == 0 -> {
                            fadeIn(tween(dur)) togetherWith fadeOut(tween(dur / 2))
                        }
                        // Avanzar: el nuevo paso entra desde la derecha.
                        targetState > initialState -> {
                            (slideInHorizontally(tween(dur)) { it } + fadeIn(tween(dur))) togetherWith
                                (slideOutHorizontally(tween(dur)) { -it / 4 } + fadeOut(tween(dur)))
                        }
                        // Retroceder: el nuevo paso entra desde la izquierda.
                        else -> {
                            (slideInHorizontally(tween(dur)) { -it } + fadeIn(tween(dur))) togetherWith
                                (slideOutHorizontally(tween(dur)) { it / 4 } + fadeOut(tween(dur)))
                        }
                    }
                }
            ) { step ->
                when (step) {
                    0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AvicolaPrimary)
                    }
                    1 -> Step1Identification(
                        ui = ui,
                        vm = vm,
                        onNext = {
                            // Auto-save: persistimos identificación antes de avanzar.
                            vm.guardarIdentificacion()
                            currentStep = 2
                        }
                    )
                    2 -> Step3GlobalReception(ui, vm, onNext = {
                        // Auto-save: persistimos recepción (aves/peso por parcela).
                        vm.guardarRecepcion()
                        // Refrescar galeras desde el pool para que el resumen del
                        // Paso 3 muestre los valores recién digitados.
                        vm.sincronizarGalerasConPool()
                        currentStep = 3
                    })
                    3 -> Step4DistributionReview(ui, editando = editandoActivo, onDone = { vm.guardar(onDone) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Step1Identification(
    ui: SetupUiState,
    vm: SetupViewModel,
    onNext: () -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        val date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                        vm.setFechaInicio(date.toString())
                    }
                    showDatePicker = false
                }) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") }
            }
        ) { DatePicker(state = datePickerState) }
    }

    val canContinue = ui.numero.length == 4 && ui.lote.isNotBlank() && ui.fechaInicio.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        AppPanel(title = "Identificación") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FormField("Nº de partida", ui.numero, vm::setNumero, Modifier.weight(1f), placeholder = "0001 (4 díg.)", keyboardType = KeyboardType.Number)
                    DateField("Fecha de ingreso", ui.fechaInicio, { showDatePicker = true }, Modifier.weight(1f))
                }
                LotesField(ui.lotes, vm)
            }
        }

        // Resumen de la distribución cargada desde el .davi (solo lectura).
        if (ui.parcelsPerTratamiento.isNotEmpty()) {
            AppPanel(title = "Distribución cargada") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val totalParcelas = ui.parcelsPerTratamiento.values.sum()
                        DistSummaryTile("Galeras", ui.numGaleras.toString(), Modifier.weight(1f))
                        DistSummaryTile("Tratamientos", ui.parcelsPerTratamiento.size.toString(), Modifier.weight(1f))
                        DistSummaryTile("Parcelas", totalParcelas.toString(), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    ui.parcelsPerTratamiento.forEach { (label, count) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Tratamiento $label", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("$count parcelas", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onNext,
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (canContinue) AvicolaPrimary else Line
            )
        ) {
            Text("Siguiente", fontWeight = FontWeight.Bold, color = if (canContinue) Color.White else TextTertiary)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = if (canContinue) Color.White else TextTertiary)
        }
    }
}

@Composable
/** Entrada de lotes: lista de filas, cada lote con su edad inicial editable. */
private fun LotesField(lotes: List<LoteEntry>, vm: SetupViewModel) {
    var nuevo by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val agregar = {
            val n = nuevo.trim()
            if (n.isNotBlank()) { vm.addLote(n); nuevo = "" }
        }
        val active = nuevo.isNotBlank()

        // Encabezado + contador de lotes
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Lotes", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = TextTertiary)
            if (lotes.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(999.dp), color = Green50) {
                    Text(
                        "${lotes.size}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                        fontSize = 10.sp, fontWeight = FontWeight.Black, color = GreenBrandDk
                    )
                }
            }
        }

        // KPI: edad promedio (en semanas)
        if (lotes.isNotEmpty()) {
            val edades = lotes.mapNotNull { it.edad.toIntOrNull() }
            val prom = if (edades.isNotEmpty()) edades.sum().toDouble() / edades.size else 0.0
            val promTxt = if (prom % 1.0 == 0.0) "${prom.toInt()}" else String.format(java.util.Locale.US, "%.1f", prom)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Green50,
                border = androidx.compose.foundation.BorderStroke(1.dp, Green100)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Analytics, null, tint = GreenBrandDk, modifier = Modifier.size(18.dp))
                        Text("Edad promedio", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextSecondary)
                    }
                    Text("$promTxt sem", fontSize = 16.sp, fontWeight = FontWeight.Black, color = GreenBrandDk)
                }
            }
        }

        // Tabla doble: 2 lotes por fila (máximo aprovechamiento del espacio)
        if (lotes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lotes.chunked(2).forEachIndexed { fila, par ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val i0 = fila * 2
                        LoteCell(
                            entry = par[0],
                            onEdad = { vm.setLoteEdad(i0, it) },
                            onRemove = { vm.removeLote(i0) },
                            modifier = Modifier.weight(1f)
                        )
                        if (par.size > 1) {
                            val i1 = fila * 2 + 1
                            LoteCell(
                                entry = par[1],
                                onEdad = { vm.setLoteEdad(i1, it) },
                                onRemove = { vm.removeLote(i1) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Campo con botón "+" integrado: gris si está vacío, verde al escribir.
        OutlinedTextField(
            value = nuevo,
            onValueChange = { nuevo = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text("Agregar lote…", fontSize = 14.sp, color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.FlutterDash, null, tint = TextMuted, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                Surface(
                    onClick = agregar,
                    enabled = active,
                    shape = CircleShape,
                    color = if (active) AvicolaPrimary else Border,
                    modifier = Modifier.padding(end = 6.dp).size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Add, "Agregar lote",
                            tint = if (active) Color.White else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Words),
            keyboardActions = KeyboardActions(onDone = { agregar() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AvicolaPrimary,
                unfocusedBorderColor = Border,
                cursorColor = AvicolaPrimary
            )
        )

        Text(
            if (lotes.isEmpty()) "Agrega uno o más lotes; cada uno con su edad inicial (semanas)."
            else "Escribe la edad (en semanas) directamente en cada lote.",
            fontSize = 11.sp, color = TextMuted
        )
    }
}

@Composable
private fun LoteCell(
    entry: LoteEntry,
    onEdad: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                entry.nombre,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 1
            )
            EdadInput(value = entry.edad, onChange = onEdad)
            Surface(
                onClick = onRemove,
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier.size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, "Quitar lote", modifier = Modifier.size(14.dp), tint = TextMuted)
                }
            }
        }
    }
}

/** Campo numérico compacto para la edad (días), editable inline. El "0" es solo
 *  placeholder: el campo se muestra vacío para escribir sin estorbos. */
@Composable
private fun EdadInput(value: String, onChange: (String) -> Unit) {
    val shown = if (value == "0") "" else value
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Green50,
        border = androidx.compose.foundation.BorderStroke(1.dp, Green100),
        modifier = Modifier.height(30.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = shown,
                onValueChange = onChange,
                modifier = Modifier.width(24.dp),
                textStyle = TextStyle(textAlign = TextAlign.End, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GreenSuccessText),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterEnd) {
                        if (shown.isEmpty()) {
                            Text("0", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                        }
                        inner()
                    }
                }
            )
            Text(" sem", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted)
        }
    }
}

/** Tile compacto para el resumen de distribución (Paso 1). */
@Composable
private fun DistSummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Green50,
        border = androidx.compose.foundation.BorderStroke(1.dp, Green100)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Black, color = GreenBrandDk)
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextTertiary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Step3GlobalReception(ui: SetupUiState, vm: SetupViewModel, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Identificamos las líneas físicas (A, B, C, D)
    val lines = remember(ui.poolParcelas, ui.numGaleras) {
        val list = mutableListOf<Pair<String, List<ParcelaSetup>>>()
        (1..ui.numGaleras).forEach { gi ->
            val gId = "G$gi"
            val line1Id = if (gId == "G1") "A" else "C"
            val line2Id = if (gId == "G1") "B" else "D"

            list.add("Línea $line1Id" to ui.poolParcelas.values.filter { it.id.startsWith("$gId$line1Id") }.sortedBy { it.id })
            list.add("Línea $line2Id" to ui.poolParcelas.values.filter { it.id.startsWith("$gId$line2Id") }.sortedBy { it.id })
        }
        list
    }

    val pagerState = rememberPagerState(pageCount = { lines.size })

    // Validación global memoizada — sólo se recalcula cuando cambia el pool.
    val validacion by remember(ui.poolParcelas) {
        derivedStateOf {
            val parcelas = ui.poolParcelas.values
            var v = 0; var i = 0; var f = 0
            for (p in parcelas) when (p.estado) {
                EstadoRecepcion.VACIA          -> v++
                EstadoRecepcion.INCOMPLETA     -> i++
                EstadoRecepcion.FUERA_DE_RANGO -> f++
                EstadoRecepcion.OK             -> { /* no-op */ }
            }
            ValidacionRecepcion(
                total       = parcelas.size,
                vacias      = v,
                incompletas = i,
                fueraRango  = f
            )
        }
    }
    val vacias       = validacion.vacias
    val incompletas  = validacion.incompletas
    val fueraRango   = validacion.fueraRango
    val total        = validacion.total
    val puedeAvanzar = validacion.puedeAvanzar

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            contentColor = AvicolaPrimary,
            divider = {}
        ) {
            lines.forEachIndexed { index, (title, _) ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            pageSpacing = 16.dp,
            // 0 = solo compone la página visible en el primer frame (evita el jank
            // de inflar páginas vecinas al revelar el paso). Las vecinas se componen
            // al deslizar, ya con la pantalla asentada.
            beyondViewportPageCount = 0
        ) { page ->
            val (title, parcels) = lines[page]
            
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header de la Tarjeta (sin KPIs)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = SurfaceAlt,
                        border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Panel de Recepción", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = TextPrimary)
                                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = AvicolaPrimary, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    HorizontalDivider(color = Border)

                    if (parcels.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No hay parcelas para esta línea", color = TextTertiary)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("PARC", modifier = Modifier.width(50.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = TextMuted)
                                    Text("AVES", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = TextMuted)
                                    Text("PESO TOTAL (g)", modifier = Modifier.weight(1.3f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = TextMuted)
                                }
                            }
                            items(parcels, key = { it.id }) { p ->
                                ReceptionRowMinimal(p, vm)
                                Spacer(Modifier.height(4.dp))
                            }
                            item { Spacer(Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }

        // ── Footer: banner de validación + botón Siguiente ──
        Surface(modifier = Modifier.fillMaxWidth(), color = Color.White, shadowElevation = 8.dp) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {

                // Banner solo si hay algo pendiente o con error.
                if (!puedeAvanzar && total > 0) {
                    val (icon, tint, bg, mensaje) = when {
                        fueraRango > 0 -> Quad(
                            Icons.Default.WarningAmber, Error, Error.copy(alpha = 0.08f),
                            "$fueraRango parcela${if (fueraRango > 1) "s" else ""} con valores fuera de rango " +
                                "(aves ${ParcelaSetup.RANGO_AVES.first}–${ParcelaSetup.RANGO_AVES.last}, " +
                                "${ParcelaSetup.RANGO_PESO_AVE.start.toInt()}–${ParcelaSetup.RANGO_PESO_AVE.endInclusive.toInt()} g/ave)"
                        )
                        incompletas > 0 -> Quad(
                            Icons.Default.WarningAmber, Warning, Warning.copy(alpha = 0.1f),
                            "$incompletas parcela${if (incompletas > 1) "s" else ""} con un solo campo digitado"
                        )
                        else -> Quad(
                            Icons.Default.HourglassEmpty, AvicolaPrimary, AvicolaPrimary.copy(alpha = 0.08f),
                            "Te falta digitar $vacias de $total parcela${if (total > 1) "s" else ""}"
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = bg,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
                            Text(
                                mensaje,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = tint
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Button(
                    onClick = onNext,
                    enabled = puedeAvanzar,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AvicolaPrimary,
                        disabledContainerColor = Border
                    )
                ) {
                    Text(
                        if (puedeAvanzar) "Ver Distribución Final" else "Completá las parcelas",
                        fontWeight = FontWeight.Black,
                        color = if (puedeAvanzar) Color.White else TextTertiary
                    )
                    if (puedeAvanzar) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.ChevronRight, null, tint = Color.White)
                    }
                }
            }
        }
    }
}

/** Helper inmutable de 4 elementos para devolver el mensaje + estilo del banner. */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** Resumen de validación del Paso 2 (memoizado con derivedStateOf). */
private data class ValidacionRecepcion(
    val total: Int,
    val vacias: Int,
    val incompletas: Int,
    val fueraRango: Int
) {
    val puedeAvanzar: Boolean
        get() = total > 0 && vacias == 0 && incompletas == 0 && fueraRango == 0
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Step4DistributionReview(ui: SetupUiState, editando: Boolean = false, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val allCorrales = remember(ui.galeras) {
        ui.galeras.flatMap { g -> g.corrales.map { g.id to it } }
    }
    val pagerState = rememberPagerState(pageCount = { allCorrales.size })

    var showConfirmDialog by remember { mutableStateOf(false) }

    // Confirmación final: "Empezar el lote" es irreversible (crea la Semana 1 y el
    // lote sale del asistente; ya no se vuelve a editar la recepción desde aquí).
    if (showConfirmDialog) {
        val totalAves = ui.galeras.sumOf { g -> g.corrales.sumOf { c -> c.parcelas.sumOf { it.avesNum } } }
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            icon = { Icon(Icons.Default.WarningAmber, null, tint = Warning) },
            title = { Text(if (editando) "¿Guardar cambios?" else "¿Empezar el lote?", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (editando)
                            "Se actualizará la recepción (aves y peso) del lote. Los datos ya " +
                            "digitados en las semanas se conservan; los indicadores se recalculan " +
                            "con los nuevos valores."
                        else
                            "Al empezar el lote se registra la recepción y comienza el seguimiento. " +
                            "Esta información ya no se podrá editar desde el asistente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Surface(
                        color = Background,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("• Partida ${ui.numero}  ·  Lote ${ui.lote}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("• ${ui.galeras.sumOf { it.corrales.size }} tratamientos  ·  $totalAves aves", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(
                        "Revisá que los datos estén correctos antes de continuar.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        onDone()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OkGreen)
                ) {
                    Text(if (editando) "Sí, guardar" else "Sí, empezar", fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Revisar de nuevo", fontWeight = FontWeight.Bold, color = TextSecondary)
                }
            }
        )
    }

    // Índice del primer tratamiento (pager page) de cada galera, en orden.
    val galeraEntries = remember(allCorrales) {
        val seen = linkedMapOf<String, Int>()
        allCorrales.forEachIndexed { idx, (gId, _) -> if (gId !in seen) seen[gId] = idx }
        seen.toList() // List<Pair<galeraId, firstPageIndex>>
    }
    val currentGaleraId = allCorrales.getOrNull(pagerState.currentPage)?.first ?: ""

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Encabezado: chips de galera (todas las galeras del lote) + posición ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                galeraEntries.forEach { (gId, firstPage) ->
                    val gNum = if (gId.length > 1) gId.drop(1) else gId
                    val isActive = gId == currentGaleraId
                    Surface(
                        onClick = { scope.launch { pagerState.animateScrollToPage(firstPage) } },
                        color = if (isActive) AvicolaPrimary else AvicolaPrimary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isActive) AvicolaPrimary else AvicolaPrimary.copy(alpha = 0.2f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Home,
                                null,
                                tint = if (isActive) Color.White else AvicolaPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "GALERA $gNum",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Black,
                                color = if (isActive) Color.White else AvicolaPrimary,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
            if (allCorrales.isNotEmpty()) {
                Text(
                    "${pagerState.currentPage + 1}/${allCorrales.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // ── Tabs de tratamientos con badge de conteo ──
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            contentColor = AvicolaPrimary,
            edgePadding = 20.dp,
            divider = {}
        ) {
            allCorrales.forEachIndexed { index, (_, corral) ->
                val label = corral.id.split("-").last()
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Surface(shape = CircleShape, color = AvicolaPrimary.copy(alpha = 0.12f)) {
                                Text(
                                    "${corral.parcelas.size}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    fontSize = 9.sp, fontWeight = FontWeight.Black, color = AvicolaPrimary
                                )
                            }
                        }
                    }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            pageSpacing = 12.dp,
            // 0 = solo la página visible en el primer frame (evita jank al revelar).
            beyondViewportPageCount = 0
        ) { page ->
            val (_, corral) = allCorrales[page]

            // KPIs y captura del tratamiento
            val totBirds = corral.parcelas.sumOf { it.inicio.toIntOrNull() ?: 0 }
            val totWeight = corral.parcelas.sumOf { it.pesoCaja.toDoubleOrNull() ?: 0.0 }
            val avgWeight = if (totBirds > 0) totWeight / totBirds else 0.0
            val parcelsWithData = corral.parcelas.count { (it.inicio.toIntOrNull() ?: 0) > 0 }
            val completion = if (corral.parcelas.isNotEmpty())
                parcelsWithData / corral.parcelas.size.toFloat() else 0f

            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ── Hero: resumen de recepción + KPIs + barra de captura ──
                    // (No repetimos el nombre del tratamiento: ya está en la pestaña.)
                    Surface(color = SurfaceAlt, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Resumen de recepción",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Black,
                                        color = TextPrimary
                                    )
                                    Text(
                                        "${corral.parcelas.size} parcelas · $parcelsWithData con datos",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextTertiary
                                    )
                                }
                                // Badge porcentaje captura
                                Surface(
                                    color = (if (completion == 1f) OkGreen else AvicolaPrimary).copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        "${(completion * 100).toInt()}%",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Black,
                                        color = if (completion == 1f) GreenSuccessText else AvicolaPrimary
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            LinearProgressIndicator(
                                progress = { completion },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                color = if (completion == 1f) OkGreen else AvicolaPrimary,
                                trackColor = Border
                            )

                            Spacer(Modifier.height(12.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LineKpiSmall("AVES", totBirds.toString(), Icons.Default.FlutterDash, GreenSuccessText, Modifier.weight(1f))
                                LineKpiSmall("PESO", String.format(Locale.US, "%.0f g", totWeight), Icons.Default.MonitorWeight, AccentPeso, Modifier.weight(1.2f))
                                LineKpiSmall("PROM", String.format(Locale.US, "%.1f g", avgWeight), Icons.Default.Analytics, AccentAlimento, Modifier.weight(1f))
                            }
                        }
                    }

                    HorizontalDivider(color = Border)

                    if (corral.parcelas.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Sin parcelas en este tratamiento", color = TextTertiary)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(corral.parcelas, key = { it.id }) { parcela ->
                                ReceptionTileReview(parcela)
                            }
                            item { Spacer(Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }

        Surface(modifier = Modifier.fillMaxWidth(), color = Color.White, shadowElevation = 8.dp) {
            Button(
                onClick = { showConfirmDialog = true },
                enabled = !ui.saving,
                modifier = Modifier.padding(20.dp).fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OkGreen)
            ) {
                if (ui.saving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                else {
                    Text(if (editando) "Guardar cambios" else "Empezar el lote", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.DoneAll, null)
                }
            }
        }
    }
}

@Composable
private fun LineKpiSmall(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(20.dp).background(color.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(12.dp), tint = color)
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text(label, fontSize = 7.sp, fontWeight = FontWeight.Black, color = TextMuted)
                Text(value, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            }
        }
    }
}

@Composable
private fun ReceptionRowMinimal(p: ParcelaSetup, vm: SetupViewModel) {
    // Visualización por estado: borde del row y color del badge ID se adaptan.
    val borderColor = when (p.estado) {
        EstadoRecepcion.OK             -> Border
        EstadoRecepcion.VACIA          -> SurfaceMuted
        EstadoRecepcion.INCOMPLETA     -> Warning.copy(alpha = 0.5f)
        EstadoRecepcion.FUERA_DE_RANGO -> Error.copy(alpha = 0.6f)
    }
    val avesError = (p.estado == EstadoRecepcion.INCOMPLETA && p.avesNum == 0) ||
        (p.estado == EstadoRecepcion.FUERA_DE_RANGO && p.avesNum !in ParcelaSetup.RANGO_AVES)
    val pesoError = (p.estado == EstadoRecepcion.INCOMPLETA && p.pesoNum == 0.0) ||
        (p.estado == EstadoRecepcion.FUERA_DE_RANGO && p.pesoPromedio !in ParcelaSetup.RANGO_PESO_AVE)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            // Punto de estado a la izquierda
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        when (p.estado) {
                            EstadoRecepcion.OK             -> OkGreen
                            EstadoRecepcion.VACIA          -> BorderStrong
                            EstadoRecepcion.INCOMPLETA     -> Warning
                            EstadoRecepcion.FUERA_DE_RANGO -> Error
                        },
                        CircleShape
                    )
            )

            // ID de Parcela
            Surface(
                color = SurfaceMuted,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(width = 48.dp, height = 40.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val displayId = if (p.id.startsWith("G", ignoreCase = true) && p.id.getOrNull(1)?.isDigit() == true) p.id.substring(2) else p.id
                    Text(displayId, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Black)
                }
            }

            // Campo AVES
            ReceptionInputField(
                value = p.inicio,
                modifier = Modifier.weight(1f),
                onValueChange = { vm.updatePoolParcela(p.id, aves = it) },
                keyboardType = KeyboardType.Number,
                isError = avesError
            )

            // Campo PESO TOTAL
            ReceptionInputField(
                value = p.pesoCaja,
                modifier = Modifier.weight(1.3f),
                onValueChange = { vm.updatePoolParcela(p.id, peso = it) },
                keyboardType = KeyboardType.Decimal,
                isError = pesoError
            )
        }
    }
}

@Composable
private fun ReceptionInputField(
    value: String,
    modifier: Modifier,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    isError: Boolean = false
) {
    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (isError) Error.copy(alpha = 0.06f) else Color.White,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (isError) Error.copy(alpha = 0.7f) else Border
        )
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                color = if (isError) Error else TextPrimary
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) Text(
                        "0",
                        color = if (isError) Error.copy(alpha = 0.5f) else BorderStrong,
                        fontSize = 18.sp, fontWeight = FontWeight.Black
                    )
                    innerTextField()
                }
            }
        )
    }
}

/** Tile de revisión por parcela: badge ID, métricas AVES/PESO y pill de estado g/ave. */
@Composable
private fun ReceptionTileReview(p: ParcelaSetup) {
    val aves = p.inicio.toIntOrNull() ?: 0
    val peso = p.pesoCaja.toDoubleOrNull() ?: 0.0
    val hasData = aves > 0
    val hasWeight = peso > 0
    val inRange = p.pesoPromedio in 35.0..50.0
    val displayId = if (p.id.startsWith("G", ignoreCase = true) && p.id.getOrNull(1)?.isDigit() == true)
        p.id.substring(2) else p.id

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (hasData) Color.White else SurfaceMuted.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (hasData) Border else BorderStrong.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ID badge — verde si tiene datos, gris si no
            Surface(
                color = if (hasData) AvicolaPrimary else BorderStrong,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(width = 52.dp, height = 36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(displayId, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }

            // Columna AVES
            Column(modifier = Modifier.weight(1f)) {
                Text("AVES", fontSize = 8.sp, color = TextMuted, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                Text(
                    if (hasData) "$aves" else "—",
                    fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                    color = if (hasData) TextPrimary else TextTertiary
                )
            }

            // Columna PESO
            Column(modifier = Modifier.weight(1.1f)) {
                Text("PESO", fontSize = 8.sp, color = TextMuted, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                Text(
                    if (hasWeight) String.format(Locale.US, "%.0f g", peso) else "—",
                    fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                    color = if (hasWeight) TextPrimary else TextTertiary
                )
            }

            // Pill estado g/ave
            if (hasData && hasWeight) {
                Surface(
                    color = if (inRange) OkGreen.copy(alpha = 0.12f) else WarningLight,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            if (inRange) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                            null,
                            modifier = Modifier.size(11.dp),
                            tint = if (inRange) OkGreen else Warning
                        )
                        Text(
                            String.format(Locale.US, "%.1f g/ave", p.pesoPromedio),
                            fontSize = 11.sp, fontWeight = FontWeight.Black,
                            color = if (inRange) GreenSuccessText else WarningDark
                        )
                    }
                }
            } else {
                Surface(color = SurfaceMuted, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "Sin datos",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun FormField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text, placeholder: String = "") {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextTertiary, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth().height(56.dp),
            placeholder = { Text(placeholder, fontSize = 14.sp) }, keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(10.dp), singleLine = true, colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line)
        )
    }
}

@Composable
private fun DateField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextTertiary, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().height(56.dp).clickable { onClick() },
            shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line), color = Color.Transparent
        ) {
            Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = value.ifEmpty { "Seleccionar fecha" }, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = if (value.isEmpty()) TextTertiary else TextPrimary, maxLines = 1)
                Icon(Icons.Default.CalendarToday, null, tint = AvicolaPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
}
