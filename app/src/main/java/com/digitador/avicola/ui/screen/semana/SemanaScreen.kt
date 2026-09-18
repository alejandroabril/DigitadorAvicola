package com.digitador.avicola.ui.screen.semana

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitador.avicola.data.repository.AppState
import com.digitador.avicola.domain.*
import com.digitador.avicola.ui.components.*
import com.digitador.avicola.ui.screen.digitacion.DigitacionCategory
import com.digitador.avicola.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanaScreen(
    semanaNumero: Int,
    onIngreso:   (Int, String, DigitacionCategory, String) -> Unit,
    onResumen:   (Int) -> Unit,
    onAjustes:   () -> Unit = {},
    onReset:     () -> Unit,
    vm: SemanaViewModel = hiltViewModel()
) {
    // Carga inicial con el número de ruta (solo la primera vez). Los cambios de
    // semana posteriores son internos (vm.cargar) y NO navegan ni recargan.
    // Al volver de digitar, esto NO reinicia la semana visible.
    LaunchedEffect(Unit) { vm.cargarInicial(semanaNumero) }

    val ui by vm.ui.collectAsState()
    val porTratamiento by vm.modoPorTratamiento.collectAsState()
    val st = ui.appState
    val partida = st.partida
    val snackbarHostState = remember { SnackbarHostState() }
    // Semana mostrada = la que maneja el ViewModel internamente.
    val semana = ui.semanaActual

    // Diálogos de "Terminar semana".
    var showTerminarConfirm by remember { mutableStateOf(false) }
    var validacionIncompleta by remember { mutableStateOf<com.digitador.avicola.domain.ValidacionSemana?>(null) }
    // Semana a desbloquear (tras mantener presionado su círculo + PIN).
    var semanaParaDesbloquear by remember { mutableStateOf<Int?>(null) }

    semanaParaDesbloquear?.let { n ->
        PinDialog(
            onVerify = { vm.verificarPin(it) },
            titulo = "Desbloquear semana $n",
            onDismiss = { semanaParaDesbloquear = null },
            onSuccess = {
                vm.reabrirSemana(n)
                semanaParaDesbloquear = null
            }
        )
    }

    BackHandler {
        vm.showResetDialog(true)
    }

    LaunchedEffect(ui.error) {
        if (ui.error.isNotBlank()) {
            snackbarHostState.showSnackbar(ui.error)
            vm.clearError()
        }
    }

    if (ui.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AvicolaPrimary)
        }
        return
    }

    if (ui.showResetDialog) {
        AlertDialog(
            onDismissRequest = { vm.showResetDialog(false) },
            title   = { Text("Volver al Historial") },
            text    = { Text("¿Deseas salir de esta partida?") },
            confirmButton = {
                TextButton(onClick = onReset) {
                    Text("Salir", color = AvicolaPrimary, fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.showResetDialog(false) }) { Text("Cancelar") }
            }
        )
    }

    if (ui.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { vm.showDeleteConfirm(false) },
            title = { Text("Eliminar Semana") },
            text = { Text("¿Estás seguro de que deseas eliminar la semana $semana? Todos los datos cargados en esta semana se perderán definitivamente.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.borrarSemanaActual { next -> vm.cargar(next) }
                }) {
                    Text("Eliminar", color = TextPrimary, fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.showDeleteConfirm(false) }) { Text("Cancelar") }
            }
        )
    }

    // Confirmación de terminar semana (cuando está completa).
    if (showTerminarConfirm) {
        AlertDialog(
            onDismissRequest = { showTerminarConfirm = false },
            icon = { Icon(Icons.Default.Lock, null, tint = Warning) },
            title = { Text("¿Terminar la semana $semana?", fontWeight = FontWeight.Black) },
            text = {
                Text("La semana quedará cerrada y ya no podrás editar pesos, alimento ni mortalidad de esta semana. Asegurate de que todo esté correcto.")
            },
            confirmButton = {
                Button(
                    onClick = { vm.cerrarSemana(semana); showTerminarConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = OkGreen)
                ) { Text("Sí, terminar", fontWeight = FontWeight.Black) }
            },
            dismissButton = {
                TextButton(onClick = { showTerminarConfirm = false }) { Text("Revisar de nuevo") }
            }
        )
    }

    // Aviso de semana incompleta (falta digitar).
    validacionIncompleta?.let { v ->
        AlertDialog(
            onDismissRequest = { validacionIncompleta = null },
            icon = { Icon(Icons.Default.WarningAmber, null, tint = Warning) },
            title = { Text("Semana incompleta", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No podés terminar la semana hasta completar la digitación:")
                    if (v.faltanPeso > 0)
                        Text("• ${v.faltanPeso} parcela${if (v.faltanPeso == 1) "" else "s"} sin peso", fontWeight = FontWeight.Bold)
                    if (v.faltanAlimento > 0)
                        Text("• ${v.faltanAlimento} parcela${if (v.faltanAlimento == 1) "" else "s"} sin alimento completo", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(onClick = { validacionIncompleta = null }) { Text("Entendido", fontWeight = FontWeight.Bold) }
            }
        )
    }

    val semanas    = st.semanas
    val semActual  = st.getSemana(semana)
    val semanaCerrada = semActual?.cerrada == true
    val isLastWeek = semanas.maxOfOrNull { it.numero } == semana && semanas.size > 1

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AvicolaPrimary)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { vm.showResetDialog(true) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = partida?.numero?.ifBlank { "Producción" } ?: "Producción",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(
                            text = "GESTIÓN DIARIA",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (isLastWeek) {
                        IconButton(onClick = { vm.showDeleteConfirm(true) }) {
                            Icon(Icons.Default.DeleteSweep, null, tint = Color.White)
                        }
                    }
                    IconButton(onClick = onAjustes) {
                        Icon(Icons.Default.Settings, null, tint = Color.White)
                    }
                }
            }
        },
        snackbarHost = {
            // Snackbar con animación de aparición/desaparición (desliza desde abajo +
            // desvanece + escala) y un ícono. Guardamos el último dato para poder
            // renderizar el contenido mientras corre la animación de SALIDA (cuando
            // currentSnackbarData ya volvió a null).
            val data = snackbarHostState.currentSnackbarData
            var lastData by remember { mutableStateOf<SnackbarData?>(null) }
            LaunchedEffect(data) { if (data != null) lastData = data }
            AnimatedVisibility(
                visible = data != null,
                enter = fadeIn(tween(220)) +
                    slideInVertically(tween(280)) { it / 2 } +
                    scaleIn(tween(220), initialScale = 0.9f),
                exit = fadeOut(tween(180)) +
                    slideOutVertically(tween(240)) { it / 2 } +
                    scaleOut(tween(180), targetScale = 0.9f)
            ) {
                (data ?: lastData)?.let { d ->
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Surface(
                            color = Ink,
                            shape = RoundedCornerShape(14.dp),
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info, null,
                                    tint = Warning,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    d.visuals.message,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = SurfaceAlt
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // ── 1. BARRA DE SEMANAS ──
            WeekNavigationBar(
                semanas = semanas,
                currentSem = semana,
                getProgreso = { sn -> ui.progresoPorSemana[sn]?.pct ?: 0 },
                onSelect = { vm.cargar(it) },   // cambio en caliente, sin navegar
                onLongPress = { n ->
                    if (vm.semanaCerrada(n)) {
                        // Si el PIN está deshabilitado, se desbloquea directo; si no, pide PIN.
                        if (vm.pinHabilitado()) semanaParaDesbloquear = n else vm.reabrirSemana(n)
                    }
                },
                onAdd = { vm.agregarSemana() }
            )

            // ── 2. SELECTOR DE VISTA: por línea / por tratamiento ──
            VistaToggle(
                porTratamiento = porTratamiento,
                onChange = { vm.setModoPorTratamiento(it) }
            )

            // ── 3. LISTA DE GALPONES ──
            Text(
                "Digitación por Galpón",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                fontWeight = FontWeight.Black
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (partida != null && partida.galeras.isNotEmpty()) {
                    items(partida.galeras, key = { it.id }) { g ->
                        val prog = remember(ui.appState, semana) {
                            vm.getProgresoGalera(g, semana)
                        }
                        
                        // Grupos según el modo: tratamientos (K1, K2… orden numérico) o
                        // líneas (A/B/C/D, de las letras del id de parcela).
                        val groups = remember(g, porTratamiento) {
                            if (porTratamiento) {
                                g.corrales.map { it.id.substringAfterLast("-") }
                                    .distinct()
                                    .sortedBy { lbl -> lbl.filter { it.isDigit() }.toIntOrNull() ?: 0 }
                            } else {
                                g.corrales.flatMap { it.parcelas }
                                    .map { id ->
                                        val letters = id.id.filter { it.isLetter() }
                                        if (letters.startsWith("G", ignoreCase = true) && letters.length > 1) {
                                            letters.substring(1)
                                        } else if (letters.isEmpty()) {
                                            id.id.take(2)
                                        } else {
                                            letters
                                        }
                                    }
                                    .distinct().filter { it.isNotBlank() }.sorted()
                            }
                        }

                        GaleraDigitacionItem(
                            label = g.nombre,
                            progreso = prog.pct,
                            groups = groups,
                            porTratamiento = porTratamiento,
                            onOptionClick = { cat, group -> onIngreso(semana, g.id, cat, group) }
                        )
                    }
                } else {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No hay galeras configuradas", color = Color.Gray)
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }

            // ── FOOTER: terminar (ícono) + análisis (principal), en una sola fila ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Terminar semana → botón-ícono (candado).
                    if (semanaCerrada) {
                        // Cerrada: candado ámbar, no clickeable (indica solo lectura).
                        Surface(
                            modifier = Modifier.size(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Warning.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Warning.copy(alpha = 0.5f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Lock, "Semana terminada", tint = Warning, modifier = Modifier.size(20.dp))
                            }
                        }
                    } else {
                        Surface(
                            onClick = {
                                val v = vm.validarSemana(semana)
                                if (v.completa) showTerminarConfirm = true
                                else validacionIncompleta = v
                            },
                            modifier = Modifier.size(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, AvicolaPrimary)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Lock, "Terminar semana", tint = AvicolaPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    // ¿Cómo va la semana? → botón principal, ocupa el resto.
                    Button(
                        onClick = { onResumen(semana) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GreenDeep,
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(Icons.Default.Analytics, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "¿Cómo va la semana?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekNavigationBar(
    semanas: List<Semana>,
    currentSem: Int,
    getProgreso: (Int) -> Int,
    onSelect: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onAdd: () -> Unit
) {
    val semActual = semanas.find { it.numero == currentSem }
    val rangoFechas = formatRangoFechas(semActual?.fechaInicio, semActual?.fechaFin)

    // El carrusel siempre centra la semana seleccionada. Cuando hay muchas semanas y el
    // LazyRow se puede desplazar, llevamos la dona activa al centro del visor.
    val listState = rememberLazyListState()
    val targetIndex = semanas.indexOfFirst { it.numero == currentSem }
    LaunchedEffect(targetIndex, semanas.size) {
        if (targetIndex < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        // Curva suave (ease-in-out) en vez del spring por defecto → sensación menos brusca.
        val spec = tween<Float>(durationMillis = 350, easing = FastOutSlowInEasing)
        if (visible != null) {
            // Si la semana YA está completamente visible, no movemos el carrusel: así no
            // quedan círculos vecinos recortados por desplazar de más.
            val fullyVisible = visible.offset >= info.viewportStartOffset &&
                visible.offset + visible.size <= info.viewportEndOffset
            if (!fullyVisible) {
                val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                val itemCenter = visible.offset + visible.size / 2f
                listState.animateScrollBy(itemCenter - viewportCenter, spec)
            }
        } else {
            // No está a la vista (carrusel largo): la traemos centrada en una sola animación.
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val itemSize = info.visibleItemsInfo.firstOrNull()?.size ?: 0
            listState.animateScrollToItem(targetIndex, -(viewport / 2 - itemSize / 2))
        }
    }

    // Tarjeta centrada: las donas + "Nueva" se centran cuando entran todas; si hay
    // muchas semanas, el LazyRow vuelve a permitir desplazamiento. La fecha va en una
    // pastilla centrada debajo.
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceMuted)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(semanas) { sem ->
                        WeekDonut(
                            numero = sem.numero,
                            pct = getProgreso(sem.numero),
                            active = sem.numero == currentSem,
                            cerrada = sem.cerrada,
                            onClick = { onSelect(sem.numero) },
                            onLongClick = { onLongPress(sem.numero) }
                        )
                    }
                    item {
                        AddWeekButton(onClick = onAdd)
                    }
                }
                // Difuminado en los bordes: overlay que MATCHEA la altura real de la fila
                // (matchParentSize) para NO forzar que la tarjeta crezca. Cuando hay semanas
                // fuera de vista se desvanecen hacia el blanco; solo del lado con contenido oculto.
                Box(Modifier.matchParentSize()) {
                    val fadeW = 28.dp
                    if (listState.canScrollBackward) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(fadeW)
                                .background(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                        )
                    }
                    if (listState.canScrollForward) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(fadeW)
                                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White)))
                        )
                    }
                }
            }
            // Fecha en pastilla centrada.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = AvicolaPrimary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CalendarToday, null,
                            modifier = Modifier.size(13.dp),
                            tint = AvicolaPrimary
                        )
                        Text(
                            "Semana ${currentSem.toString().padStart(2, '0')} · $rangoFechas",
                            style = MaterialTheme.typography.bodySmall,
                            color = AvicolaPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Visualización de una semana como "mini-dona" de progreso: el número va centrado
 * dentro de un anillo que se rellena según el % de avance. El color refleja el estado
 * (activa / completa / parcial / vacía) y debajo se muestra el % o un check.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekDonut(
    numero: Int,
    pct: Int,
    active: Boolean,
    cerrada: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val complete = pct >= 100
    val target = pct.coerceIn(0, 100) / 100f
    val sweep by animateFloatAsState(targetValue = target, label = "weekSweep")

    val track = Border
    // Semana cerrada → anillo lleno en verde de éxito.
    val ringColor = when {
        cerrada  -> StateSuccess
        complete -> StateSuccess
        active   -> AvicolaPrimary
        pct > 0  -> AvicolaPrimary.copy(alpha = 0.7f)
        else     -> BorderStrong
    }
    val numberColor = when {
        active   -> AvicolaPrimary
        complete -> GreenSuccessText
        else     -> TextPrimary
    }
    val diameter = if (active) 60.dp else 52.dp
    val strokeDp = if (active) 6.dp else 5.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .then(if (active) Modifier.background(AvicolaPrimary.copy(alpha = 0.08f)) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val s = strokeDp.toPx()
                val inset = s / 2f
                val arc = Size(size.width - s, size.height - s)
                drawArc(
                    color = track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arc,
                    style = Stroke(width = s, cap = StrokeCap.Round)
                )
                if (sweep > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arc,
                        style = Stroke(width = s, cap = StrokeCap.Round)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "SEM",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = numberColor.copy(alpha = 0.6f)
                )
                Text(
                    text = numero.toString(),
                    fontSize = if (active) 18.sp else 16.sp,
                    fontWeight = FontWeight.Black,
                    color = numberColor
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            // Semana terminada → candado en lugar del check.
            cerrada -> Icon(
                Icons.Default.Lock, null,
                modifier = Modifier.size(14.dp),
                tint = StateSuccess
            )
            complete -> Icon(
                Icons.Default.CheckCircle, null,
                modifier = Modifier.size(14.dp),
                tint = StateSuccess
            )
            else -> Text(
                text = "$pct%",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) AvicolaPrimary else TextMuted
            )
        }
    }
}

@Composable
private fun AddWeekButton(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(SurfaceMuted)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, null, tint = AvicolaPrimary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Nueva",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted
        )
    }
}

private fun formatRangoFechas(inicio: String?, fin: String?): String {
    if (inicio.isNullOrBlank()) return "Sin fechas"
    val di = inicio.split("-")
    val df = fin?.split("-")
    if (di.size != 3) return "Sin fechas"
    val meses = listOf("ene","feb","mar","abr","may","jun","jul","ago","sep","oct","nov","dic")
    val mesIdx = (di[1].toIntOrNull() ?: 1) - 1
    val mes = meses.getOrElse(mesIdx) { "?" }
    return if (df != null && df.size == 3) {
        "${di[2]}-${df[2]} $mes"
    } else {
        "${di[2]} $mes"
    }
}

@Composable
private fun GaleraDigitacionItem(
    label: String,
    progreso: Int,
    groups: List<String>,
    porTratamiento: Boolean = false,
    onOptionClick: (DigitacionCategory, String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceMuted)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(if (progreso >= 100) StateSuccessSurface else SurfaceMuted, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (progreso >= 100) Icons.Default.CheckCircle else Icons.Default.Domain,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (progreso >= 100) StateSuccess else TextMuted
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label, 
                        style = MaterialTheme.typography.titleMedium, 
                        fontWeight = FontWeight.Black,
                        color = TextPrimary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { progreso / 100f },
                            modifier = Modifier
                                .width(80.dp)
                                .height(4.dp)
                                .clip(CircleShape),
                            color = if (progreso >= 100) StateSuccess else AvicolaPrimary,
                            trackColor = SurfaceMuted
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$progreso%", 
                            style = MaterialTheme.typography.labelSmall, 
                            fontWeight = FontWeight.Bold,
                            color = if(progreso >= 100) StateSuccess else TextTertiary
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(20.dp))

            if (porTratamiento) {
                // Por tratamiento: hay varios grupos (K1, K2…) → cada categoría es una fila
                // con sus chips, que se deslizan si no entran. Un solo scrollState sincroniza
                // las 3 filas (MORT/PESO/ALIM) para que se muevan juntas.
                val chipsScroll = rememberScrollState()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryRow("MORT.", Icons.Default.Warning, AccentMortalidad, groups, chipsScroll) {
                        onOptionClick(DigitacionCategory.MORTALIDAD, it)
                    }
                    CategoryRow("PESO", Icons.Default.MonitorWeight, StateSuccess, groups, chipsScroll) {
                        onOptionClick(DigitacionCategory.PESO, it)
                    }
                    CategoryRow("ALIM.", Icons.Default.Restaurant, AccentAlimento, groups, chipsScroll) {
                        onOptionClick(DigitacionCategory.ALIMENTO, it)
                    }
                }
            } else {
                // Por línea: 2 grupos (A/B) → tres columnas lado a lado, como antes.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CategoryBox(
                        label = "MORT.",
                        icon = Icons.Default.Warning,
                        color = AccentMortalidad,
                        groups = groups,
                        modifier = Modifier.weight(1f),
                        onSelect = { onOptionClick(DigitacionCategory.MORTALIDAD, it) }
                    )
                    CategoryBox(
                        label = "PESO",
                        icon = Icons.Default.MonitorWeight,
                        color = StateSuccess,
                        groups = groups,
                        modifier = Modifier.weight(1f),
                        onSelect = { onOptionClick(DigitacionCategory.PESO, it) }
                    )
                    CategoryBox(
                        label = "ALIM.",
                        icon = Icons.Default.Restaurant,
                        color = AccentAlimento,
                        groups = groups,
                        modifier = Modifier.weight(1f),
                        onSelect = { onOptionClick(DigitacionCategory.ALIMENTO, it) }
                    )
                }
            }
        }
    }
}

/** Selector de vista: Por línea / Por tratamiento (segmentado). */
@Composable
private fun VistaToggle(porTratamiento: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = SurfaceMuted,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            VistaSeg("Por línea", !porTratamiento, Modifier.weight(1f)) { onChange(false) }
            VistaSeg("Por tratamiento", porTratamiento, Modifier.weight(1f)) { onChange(true) }
        }
    }
}

@Composable
private fun VistaSeg(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AvicolaPrimary else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = if (selected) Color.White else TextMuted
        )
    }
}

/** Fila de categoría para el modo POR TRATAMIENTO: etiqueta a la izquierda y los
 *  chips de tratamiento (K1, K2…) a la derecha; si no entran, se deslizan. Un
 *  degradado + chevron a la derecha avisa que hay más; a la izquierda cuando ya
 *  se deslizó. [scrollState] se comparte entre las 3 filas para moverlas juntas. */
@Composable
private fun CategoryRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    groups: List<String>,
    scrollState: ScrollState,
    onSelect: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.width(82.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(13.dp), tint = color)
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                groups.forEach { group ->
                    Surface(
                        onClick = { onSelect(group) },
                        modifier = Modifier.heightIn(min = 36.dp).widthIn(min = 36.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = SurfaceMuted,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStrong)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = group,
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            // Hay más a la derecha → degradado + chevron.
            if (scrollState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .height(36.dp)
                        .width(36.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White)))
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Deslizá para ver más tratamientos",
                    tint = AvicolaPrimary,
                    modifier = Modifier.align(Alignment.CenterEnd).size(20.dp)
                )
            }
            // Ya se deslizó → degradado a la izquierda (hay contenido oculto atrás).
            if (scrollState.canScrollBackward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .height(36.dp)
                        .width(22.dp)
                        .background(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                )
            }
        }
    }
}

@Composable
private fun CategoryBox(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    groups: List<String>,
    modifier: Modifier,
    onSelect: (String) -> Unit
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(icon, null, modifier = Modifier.size(12.dp), tint = color)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Black)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            groups.forEach { group ->
                Surface(
                    onClick = { onSelect(group) },
                    modifier = Modifier.size(height = 36.dp, width = 36.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceMuted,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderStrong)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = group,
                            style = MaterialTheme.typography.labelLarge, 
                            color = TextPrimary, 
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

