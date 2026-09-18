package com.digitador.avicola.ui.screen.digitacion

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitador.avicola.domain.Calculadora
import com.digitador.avicola.domain.DatoParcela
import com.digitador.avicola.domain.Parcela
import com.digitador.avicola.domain.RefAlimento
import com.digitador.avicola.domain.Semana
import com.digitador.avicola.ui.theme.*
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigitacionScreen(
    semanaNumero: Int,
    galeraId: String,
    initialCategory: DigitacionCategory,
    initialGroup: String,
    onBack: () -> Unit,
    vm: DigitacionViewModel = hiltViewModel()
) {
    LaunchedEffect(semanaNumero, galeraId) {
        vm.cargar(semanaNumero, galeraId, initialGroup)
        vm.setCategory(initialCategory)
    }

    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    
    // Manejar el botón de atrás del sistema
    BackHandler(enabled = !isSaving) {
        isSaving = true
        vm.persist { 
            isSaving = false
            onBack() 
        }
    }

    if (ui.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AvicolaPrimary)
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    DigitacionHeader(
                        ui = ui,
                        semanaNumero = semanaNumero,
                        onBack = { 
                            if (!isSaving) {
                                isSaving = true
                                vm.persist { 
                                    isSaving = false
                                    onBack() 
                                }
                            }
                        },
                        onCategorySelected = { vm.setCategory(it) },
                        onGroupSelected = { vm.setGroup(it) }
                    )
                }
            ) { padding ->
                // imePadding(): la app es edge-to-edge, así que el teclado no encoge solo
                // el contenido. Al achicar este contenedor, la LazyColumn se reduce y la
                // fila enfocada sube automáticamente por encima del teclado (bring-into-view).
                Column(modifier = Modifier.padding(padding).fillMaxSize().background(SurfaceAlt).imePadding()) {
                    if (ui.finalizada) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Warning.copy(alpha = 0.12f))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Lock, null, tint = Warning, modifier = Modifier.size(18.dp))
                            Text(
                                if (ui.motivoBloqueo == "semana")
                                    "Semana terminada · solo lectura. No se pueden registrar cambios."
                                else
                                    "Lote cerrado · solo lectura. No se pueden registrar cambios.",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = WarningDark
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (ui.activeCategory) {
                            DigitacionCategory.MORTALIDAD -> MortalidadMatrix(ui.parcels, semanaNumero, ui, vm)
                            DigitacionCategory.PESO -> PesoMatrix(ui.parcels, semanaNumero, ui, vm)
                            DigitacionCategory.ALIMENTO -> AlimentoMatrix(ui.parcels, semanaNumero, ui, vm)
                        }
                    }
                }
            }

            // Overlay de guardado profesional
            if (isSaving) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = AvicolaPrimary)
                            Spacer(Modifier.height(16.dp))
                            Text("Guardando cambios...", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DigitacionHeader(
    ui: DigitacionUiState,
    semanaNumero: Int,
    onBack: () -> Unit,
    onCategorySelected: (DigitacionCategory) -> Unit,
    onGroupSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().background(Color.White)) {
        // App Bar Solid Green
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AvicolaPrimary,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ui.galera?.nombre ?: "Galera",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        text = "SEMANA $semanaNumero · MONITOREO DIARIO",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Dashboard Selectors & KPIs (White background)
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Categorías
                Surface(
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    color = SurfaceMuted,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        DigitacionCategory.entries.forEach { cat ->
                            val selected = ui.activeCategory == cat
                            val color = when(cat) {
                                DigitacionCategory.MORTALIDAD -> AccentMortalidad
                                DigitacionCategory.PESO -> StateSuccess
                                DigitacionCategory.ALIMENTO -> AccentAlimento
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) color else Color.Transparent)
                                    .clickable { onCategorySelected(cat) },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when(cat) {
                                            DigitacionCategory.MORTALIDAD -> Icons.Default.Warning
                                            DigitacionCategory.PESO -> Icons.Default.MonitorWeight
                                            DigitacionCategory.ALIMENTO -> Icons.Default.Restaurant
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (selected) Color.White else TextMuted
                                    )
                                    if (selected) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(cat.name.take(4), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Líneas
                Surface(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    color = SurfaceMuted,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        ui.availableGroups.forEach { group ->
                            val selected = ui.activeGroup == group
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) GreenDeep else Color.Transparent)
                                    .clickable { onGroupSelected(group) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when {
                                        group.isBlank() -> "DIGITAR"
                                        ui.porTratamiento -> group          // "K1", "K7"…
                                        else -> "LÍNEA $group"
                                    },
                                    fontWeight = FontWeight.Black,
                                    color = if (selected) Color.White else TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            // Fila 3: KPIs
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiCard("Vivas", ui.kpiVivas.toString(), Icons.Default.Groups, GreenSuccessText, Modifier.weight(1f))
                KpiCard("Prom", String.format(Locale.US, "%.1f g", ui.kpiPromedio), Icons.Default.Analytics, AccentPeso, Modifier.weight(1f))
                KpiCard("Mort", ui.kpiMortalidad.toString(), Icons.Default.Dangerous, StateErrorText, Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun KpiCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(18.dp).background(color.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, modifier = Modifier.size(10.dp), tint = color)
                }
                Spacer(Modifier.width(6.dp))
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextTertiary, fontWeight = FontWeight.Bold)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = TextPrimary)
        }
    }
}

@Composable
fun MortalidadMatrix(parcels: List<Parcela>, semNum: Int, ui: DigitacionUiState, vm: DigitacionViewModel) {
    val daysData = remember(ui.semana?.fechaInicio) {
        val baseDate = try { LocalDate.parse(ui.semana?.fechaInicio) } catch (_: Exception) { null }
        val meses = listOf("ENE","FEB","MAR","ABR","MAY","JUN","JUL","AGO","SEP","OCT","NOV","DIC")
        List(7) { idx ->
            val date = baseDate?.plusDays(idx.toLong())
            val day = date?.dayOfMonth?.toString() ?: (idx + 1).toString()
            val month = if (date != null) meses[date.monthValue - 1] else ""
            month to day
        }
    }

    val monthSections = remember(daysData) {
        val result = mutableListOf<Pair<String, Int>>()
        if (daysData.isEmpty()) return@remember result
        var currentMonth = daysData[0].first
        var count = 0
        daysData.forEach { (m, _) ->
            if (m == currentMonth) { count++ } 
            else { result.add(currentMonth to count); currentMonth = m; count = 1 }
        }
        result.add(currentMonth to count)
        result
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxWidth(), color = Color.White) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(55.dp))
                    monthSections.forEach { (m, c) ->
                        Text(m, Modifier.weight(c.toFloat()), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    Spacer(Modifier.width(60.dp))
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("PARC", Modifier.width(55.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                    daysData.forEach { (_, d) ->
                        Text(d, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    Text("TOTAL", Modifier.width(60.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                }
            }
        }
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 100.dp, start = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(parcels, key = { it.id }) { p ->
                val dato = ui.datos[p.id]?.get(semNum) ?: DatoParcela(semNum, p.id)
                MortalidadRow(p.id, dato, semNum, vm, ui.finalizada)
            }
        }
    }
}

@Composable
fun MortalidadRow(pId: String, dato: DatoParcela, semNum: Int, vm: DigitacionViewModel, bloqueada: Boolean) {
    val displayId = remember(pId) { 
        if (pId.startsWith("G", ignoreCase = true) && pId.getOrNull(1)?.isDigit() == true) pId.substring(2) else pId 
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceMuted)
    ) {
        Row(modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.width(50.dp), color = StateSuccessSurface, shape = RoundedCornerShape(6.dp)) {
                Text(displayId, Modifier.padding(vertical = 4.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = GreenSuccessText)
            }
            
            dato.mort.forEachIndexed { idx, value ->
                var local by remember(value) { mutableStateOf(if (value == 0 || value == null) "" else value.toString()) }
                Box(
                    modifier = Modifier.weight(1f).height(38.dp).padding(horizontal = 1.dp)
                        .background(if(value != null && value > 0) StateErrorSurface else SurfaceAlt, RoundedCornerShape(6.dp))
                        .border(1.dp, Border, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
                        value = local,
                        onValueChange = {
                            if(it.length <= 3 && it.all { c -> c.isDigit() }) {
                                if (vm.updateMort(semNum, pId, idx, it)) {
                                    local = it
                                }
                            }
                        },
                        readOnly = bloqueada,
                        enabled = !bloqueada,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(textAlign = TextAlign.Center, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                    )
                }
            }
            Text(dato.mort.sumOf { it ?: 0 }.toString(), Modifier.width(55.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Black, color = AccentMortalidad, fontSize = 16.sp)
        }
    }
}

@Composable
fun PesoMatrix(parcels: List<Parcela>, semNum: Int, ui: DigitacionUiState, vm: DigitacionViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxWidth(), color = Color.White) {
            Row(modifier = Modifier.padding(vertical = 10.dp, horizontal = 16.dp)) {
                Text("PARC", Modifier.width(50.dp), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                Text("AVES", Modifier.width(60.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                Text("PESO TOTAL (g)", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                Text("PROM", Modifier.width(70.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            }
        }
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 100.dp, start = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(parcels, key = { it.id }) { p ->
                val datosByPar = ui.datos[p.id] ?: emptyMap()
                val dato = datosByPar[semNum] ?: DatoParcela(semNum, p.id)
                // Memoizado: solo se recalcula si los datos de ESA parcela cambian.
                val saldo = remember(p.id, datosByPar) {
                    Calculadora.getSaldoAnterior(semNum, p.id, p, datosByPar) -
                        (datosByPar[semNum]?.mort?.sumOf { it ?: 0 } ?: 0)
                }
                PesoRow(p, dato, semNum, saldo, vm, ui.finalizada)
            }
        }
    }
}

@Composable
fun PesoRow(p: Parcela, dato: DatoParcela, semNum: Int, saldo: Int, vm: DigitacionViewModel, bloqueada: Boolean) {
    // Simpler cleanId: remove "G" + digit if starts with it
    val displayId = remember(p.id) { 
        if (p.id.startsWith("G", ignoreCase = true) && p.id.getOrNull(1)?.isDigit() == true) p.id.substring(2) else p.id 
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.width(45.dp), color = StateSuccessSurfaceAlt, shape = RoundedCornerShape(12.dp)) {
                Text(displayId, Modifier.padding(vertical = 4.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = GreenDeep)
            }
            
            Text(
                text = saldo.toString(),
                modifier = Modifier.width(60.dp),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = TextSecondary
            )
            
            val initialTotal = if (dato.peso != null && saldo > 0) (dato.peso * saldo) else null
            var localTotalStr by remember(initialTotal) { mutableStateOf(initialTotal?.let { String.format(Locale.US, "%.0f", it) } ?: "") }

            Box(
                modifier = Modifier.weight(1f).height(44.dp)
                    .background(SurfaceAlt, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = localTotalStr,
                    onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) { localTotalStr = it; vm.updatePeso(semNum, p.id, it, saldo) } },
                    readOnly = bloqueada,
                    enabled = !bloqueada,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    textStyle = TextStyle(textAlign = TextAlign.Center, fontSize = 18.sp, fontWeight = FontWeight.Black),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                )
            }

            Column(modifier = Modifier.width(70.dp), horizontalAlignment = Alignment.End) {
                Text(if (dato.peso != null) String.format(Locale.US, "%.1f", dato.peso) else "—", fontWeight = FontWeight.Black, color = GreenWeight, fontSize = 16.sp)
                Text("g/ave", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun AlimentoMatrix(parcels: List<Parcela>, semNum: Int, ui: DigitacionUiState, vm: DigitacionViewModel) {
    val activeRefs = ui.semana?.refsActivas ?: listOf("BR1")
    val allPossibleRefs = listOf("BR1", "BR2", "BR3", "BR4")
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Selector de Referencias Activas
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SurfaceMuted,
            border = androidx.compose.foundation.BorderStroke(1.dp, Border)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("REFS ACTIVAS:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = TextTertiary)
                allPossibleRefs.forEach { ref ->
                    val isActive = activeRefs.contains(ref)
                    FilterChip(
                        selected = isActive,
                        onClick = { vm.toggleRef(ref) },
                        enabled = !ui.finalizada,
                        label = { Text(ref, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.height(32.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GreenDeep,
                            selectedLabelColor = Color.White
                        )
                    )
                }
                Spacer(Modifier.weight(1f))
                UnidadGramosTip()
            }
        }

        Surface(modifier = Modifier.fillMaxWidth(), color = Color.White) {
            Row(modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PARC", Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                
                // Header scrollable
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    activeRefs.forEach { ref ->
                        val excluida = ref in ui.refsExcluidas
                        Column(modifier = Modifier.width(130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                ref,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (excluida) TextMuted else GreenDeep,
                                textDecoration = if (excluida) TextDecoration.LineThrough else null
                            )
                            // Toggle para DESECHAR esta referencia del cálculo (sigue visible y
                            // digitable; solo no cuenta en los indicadores). Pill redondeado:
                            // tenue cuando se incluye, ámbar relleno cuando está excluida.
                            val cExcl = if (excluida) Warning else TextMuted
                            Surface(
                                onClick = { vm.toggleRefExcluida(ref) },
                                enabled = !ui.finalizada,
                                shape = RoundedCornerShape(50),
                                color = if (excluida) Warning.copy(alpha = 0.14f) else Color.Transparent,
                                border = BorderStroke(1.dp, cExcl.copy(alpha = if (excluida) 0.9f else 0.35f)),
                                modifier = Modifier.padding(top = 3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        if (excluida) Icons.Default.Block else Icons.Default.RemoveCircleOutline,
                                        contentDescription = if (excluida) "Referencia excluida del cálculo" else "Excluir del cálculo",
                                        tint = cExcl,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        if (excluida) "Excluida" else "Excluir",
                                        fontSize = 9.sp,
                                        color = cExcl,
                                        fontWeight = if (excluida) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text("ANT", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 8.sp, color = Color.Gray)
                                Text("ING", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 8.sp, color = Color.Gray)
                                Text("SAL", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 8.sp, color = Color.Gray)
                            }
                        }
                    }
                }
                Text("ADJ", Modifier.width(40.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 100.dp, start = 4.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(parcels, key = { it.id }) { p ->
                val dato = ui.datos[p.id]?.get(semNum) ?: DatoParcela(semNum, p.id)
                val datoAnt = ui.datos[p.id]?.get(semNum - 1)
                AlimentoRow(p.id, ui.semana, semNum, dato, datoAnt, vm, scrollState, ui.finalizada)
            }
        }
    }
}

@Composable
fun AlimentoRow(
    pId: String,
    semana: Semana?,
    semNum: Int,
    dato: DatoParcela,
    datoAnt: DatoParcela?,
    vm: DigitacionViewModel,
    scrollState: androidx.compose.foundation.ScrollState,
    bloqueada: Boolean
) {
    val activeRefs = semana?.refsActivas ?: listOf("BR1")
    val displayId = remember(pId) { if (pId.startsWith("G", ignoreCase = true) && pId.getOrNull(1)?.isDigit() == true) pId.substring(2) else pId }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.width(36.dp), color = StateSuccessSurface, shape = RoundedCornerShape(8.dp)) {
                Text(displayId, Modifier.padding(vertical = 4.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Black, fontSize = 11.sp, color = GreenSuccessText)
            }
            Spacer(Modifier.width(6.dp))
            
            // Row scrollable
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                activeRefs.forEach { tipo ->
                    val ref = dato.refs[tipo] ?: RefAlimento(tipo)
                    val refAnt = datoAnt?.refs?.get(tipo)
                    val saldoAnt = refAnt?.saldoFin ?: 0.0

                    Row(modifier = Modifier.width(130.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        // ANT (ReadOnly)
                        Box(
                            modifier = Modifier.weight(1f).height(36.dp).background(SurfaceAlt, RoundedCornerShape(6.dp)).border(0.5.dp, Border, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if(saldoAnt > 0) fmtCantidad(saldoAnt) else "0", fontSize = 11.sp, color = TextTertiary, fontWeight = FontWeight.Bold)
                        }
                        // ING
                        AlimentoCellMinimal(ref.ingreso, Modifier.weight(1f), bloqueada = bloqueada) { vm.updateRef(semNum, pId, tipo, "ingreso", it) }
                        // SAL
                        AlimentoCellMinimal(ref.saldoFin, Modifier.weight(1f), bloqueada = bloqueada) { vm.updateRef(semNum, pId, tipo, "saldoFin", it) }
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(modifier = Modifier.width(40.dp)) {
                AlimentoCellMinimal(dato.consAjust, Modifier.fillMaxWidth(), isAjuste = true, bloqueada = bloqueada) { vm.updateConsAjust(semNum, pId, it) }
            }
        }
    }
}

/** Ícono "!" con tooltip que aclara la unidad del alimento (kilogramos). */
@Composable
private fun UnidadGramosTip() {
    var showTip by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showTip = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.ErrorOutline, "Unidad de medida", tint = AvicolaPrimary, modifier = Modifier.size(20.dp))
        }
        if (showTip) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopEnd,
                offset = androidx.compose.ui.unit.IntOffset(0, 90),
                onDismissRequest = { showTip = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Ink,
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(8.dp).widthIn(max = 240.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text(
                            "El alimento se digita en kilogramos (kg): ingreso, saldo y ajuste.",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/** Formatea una cantidad para mostrar: vacío si 0/null, entero si es redondo, hasta 2 decimales. */
private fun fmtCantidad(v: Double?): String {
    if (v == null || v == 0.0) return ""
    return if (v % 1.0 == 0.0) v.toLong().toString()
           else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}

/** Acepta vacío, o dígitos con UN separador decimal (coma o punto) y hasta 2 decimales. */
private val REGEX_CANTIDAD = Regex("^\\d{0,6}([.,]\\d{0,2})?$")

@Composable
fun AlimentoCellMinimal(value: Double?, modifier: Modifier = Modifier, isAjuste: Boolean = false, bloqueada: Boolean = false, onValueChange: (String) -> Unit) {
    // Usamos TextFieldValue (texto + selección) para poder SELECCIONAR TODO al enfocar:
    // así, al tocar una casilla con valor (p. ej. replicado), el primer dígito reemplaza
    // todo sin tener que borrar a mano. Solo se resincroniza desde el modelo si cambió
    // por fuera, sin pisar la edición ni redondear "2,86" mientras se tipea.
    var tfv by remember { mutableStateOf(TextFieldValue(fmtCantidad(value))) }
    LaunchedEffect(value) {
        if (value != tfv.text.replace(',', '.').toDoubleOrNull()) tfv = TextFieldValue(fmtCantidad(value))
    }
    Box(
        modifier = modifier
            .height(36.dp)
            .background(if(isAjuste) SurfaceMuted else SurfaceAlt, RoundedCornerShape(6.dp))
            .border(1.dp, Border, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = tfv,
            onValueChange = { nv -> if (REGEX_CANTIDAD.matches(nv.text)) { tfv = nv; onValueChange(nv.text) } },
            readOnly = bloqueada,
            enabled = !bloqueada,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .onFocusChanged { fs ->
                    // Al enfocar, seleccionar todo → escribir reemplaza el valor existente.
                    if (fs.isFocused && tfv.text.isNotEmpty()) {
                        tfv = tfv.copy(selection = TextRange(0, tfv.text.length))
                    }
                },
            textStyle = TextStyle(textAlign = TextAlign.Center, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if(isAjuste) StateErrorText else TextPrimary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
    }
}
