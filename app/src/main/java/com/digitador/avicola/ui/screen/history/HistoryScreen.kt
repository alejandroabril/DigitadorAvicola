package com.digitador.avicola.ui.screen.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitador.avicola.R
import com.digitador.avicola.domain.PartidaSummary
import com.digitador.avicola.ui.components.PinDialog
import com.digitador.avicola.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onSelect: (Long) -> Unit,
    onCompletePending: (Long) -> Unit,
    onEditarActivo: (Long) -> Unit = {},
    onOpenPapelera: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
    vm: HistoryViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsState()
    val pinHabilitado by vm.pinHabilitado.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("Todos") }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    // Diálogo de PIN para acceder a la papelera (solo si el PIN está habilitado).
    var showPinDialog by remember { mutableStateOf(false) }
    if (showPinDialog) {
        PinDialog(
            onVerify = { vm.verificarPin(it) },
            onDismiss = { showPinDialog = false },
            onSuccess = {
                showPinDialog = false
                onOpenPapelera()
            }
        )
    }

    // ── Editar recepción ──
    // Long-press en un lote activo → confirmación → PIN (si está habilitado) → editar.
    var editTarget by remember { mutableStateOf<PartidaSummary?>(null) }
    var showEditPin by remember { mutableStateOf(false) }

    // 1) Confirmación de edición.
    editTarget?.let { target ->
        if (!showEditPin) {
            AlertDialog(
                onDismissRequest = { editTarget = null },
                icon = { Icon(Icons.Default.Edit, null, tint = AvicolaPrimary) },
                title = { Text("¿Editar la partida ${target.numero}?", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Vas a editar la recepción (aves y peso) de la partida ${target.numero}. " +
                        "Los datos ya digitados en las semanas se conservan."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (pinHabilitado) {
                            // Mantenemos editTarget; el PIN se muestra encima.
                            showEditPin = true
                        } else {
                            val id = target.id
                            editTarget = null
                            vm.selectPartida(id)
                            onEditarActivo(id)
                        }
                    }) { Text("Editar", color = AvicolaPrimary, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { editTarget = null }) { Text("Cancelar") }
                }
            )
        }
    }

    // 2) PIN antes de editar (mismo patrón que la papelera).
    if (showEditPin) {
        val id = editTarget?.id
        PinDialog(
            onVerify = { vm.verificarPin(it) },
            titulo = "Ingresá el PIN para editar",
            onDismiss = { showEditPin = false; editTarget = null },
            onSuccess = {
                showEditPin = false
                editTarget = null
                if (id != null) {
                    vm.selectPartida(id)
                    onEditarActivo(id)
                }
            }
        )
    }

    // Refrescar al reentrar (después de completar/cancelar un pendiente desde Setup).
    LaunchedEffect(Unit) { vm.recargar() }

    // File picker para cargar un .davi manualmente desde el FAB.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            vm.cargarDaviDesdeUri(context, it) { newId ->
                vm.selectPartida(newId)
                onCompletePending(newId)
            }
        }
    }

    // Banner de error breve.
    LaunchedEffect(ui.error) {
        if (ui.error.isNotBlank()) {
            delay(3000)
            vm.clearError()
        }
    }

    Scaffold(
        containerColor = Background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { launcher.launch("*/*") },
                containerColor = AvicolaPrimary,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Default.FileOpen, null) },
                text = { Text("Cargar archivo", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        // Solo se aplica el inset inferior (barra de navegación). El hero verde debe
        // dibujarse DETRÁS de la status bar para que esa zona sea verde, no blanca.
        Column(modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // ── HERO con animación de entrada one-shot (pop del logo + fade del texto) ──
            // Se dispara una sola vez al aparecer; no hay loops (no reintroduce lag).
            var heroVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { heroVisible = true }

            val logoScale by animateFloatAsState(
                targetValue = if (heroVisible) 1f else 0.5f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                label = "logoScale"
            )
            val logoAlpha by animateFloatAsState(
                targetValue = if (heroVisible) 1f else 0f,
                animationSpec = tween(400), label = "logoAlpha"
            )
            val tituloAlpha by animateFloatAsState(
                targetValue = if (heroVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 450, delayMillis = 120), label = "tituloAlpha"
            )
            val tituloY by animateFloatAsState(
                targetValue = if (heroVisible) 0f else 26f,
                animationSpec = tween(durationMillis = 450, delayMillis = 120), label = "tituloY"
            )
            val subAlpha by animateFloatAsState(
                targetValue = if (heroVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 450, delayMillis = 220), label = "subAlpha"
            )
            val subY by animateFloatAsState(
                targetValue = if (heroVisible) 0f else 26f,
                animationSpec = tween(durationMillis = 450, delayMillis = 220), label = "subY"
            )

            // ── Burbujas ambientales en el verde (suben lento) ──
            // Parámetros por burbuja: [xFrac, radioFrac(ancho), velocidad, fase, swayFrac].
            val burbujas = remember {
                val rnd = java.util.Random(7L)
                List(11) {
                    floatArrayOf(
                        rnd.nextFloat(),                     // x (0..1)
                        0.018f + rnd.nextFloat() * 0.05f,    // radio (frac. del ancho)
                        0.5f + rnd.nextFloat() * 0.9f,       // velocidad
                        rnd.nextFloat(),                     // fase
                        0.015f + rnd.nextFloat() * 0.05f     // amplitud de vaivén
                    )
                }
            }
            val burbTrans = rememberInfiniteTransition(label = "burbujas")
            val burbT = burbTrans.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing)),
                label = "burbT"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.38f)
                    .background(PrimaryGradient, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                    .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                    // Lee el valor animado SOLO en la fase de dibujo → redibuja el hero
                    // sin recomponer la pantalla (barato, no reintroduce lag).
                    .drawBehind {
                        val t = burbT.value
                        val w = size.width; val h = size.height
                        burbujas.forEach { b ->
                            val p = ((t * b[2]) + b[3]) % 1f         // 0..1 subiendo
                            val y = h * (1f - p)                      // de abajo hacia arriba
                            val x = w * b[0] + (Math.sin(p * 2.0 * Math.PI + b[3] * 6.2832).toFloat()) * (w * b[4])
                            val a = (Math.sin(p * Math.PI).toFloat()).coerceIn(0f, 1f) * 0.10f  // aparece/desvanece
                            drawCircle(Color.White.copy(alpha = a), radius = w * b[1], center = Offset(x, y))
                        }
                    }
                    .statusBarsPadding(),   // pinta el verde tras la status bar y baja el contenido
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier
                            .size(130.dp)
                            .graphicsLayer {
                                scaleX = logoScale; scaleY = logoScale; alpha = logoAlpha
                            }
                            .shadow(12.dp, CircleShape),
                        shape = CircleShape,
                        color = Color.White,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.pollito),
                            contentDescription = null,
                            modifier = Modifier.padding(8.dp).clip(CircleShape),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Flock Tracker",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier.graphicsLayer { alpha = tituloAlpha; translationY = tituloY }
                    )
                    Text(
                        "Gestión y Control de Lotes",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.graphicsLayer { alpha = subAlpha; translationY = subY }
                    )
                }

                // Configuración (tuerca)
                IconButton(
                    onClick = onOpenConfig,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                ) {
                    Icon(Icons.Default.Settings, "Configuración", tint = Color.White.copy(alpha = 0.9f))
                }

                // Acceso a la papelera (protegido con PIN si está habilitado)
                IconButton(
                    onClick = { if (pinHabilitado) showPinDialog = true else onOpenPapelera() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    Icon(Icons.Default.Delete, "Papelera", tint = Color.White.copy(alpha = 0.9f))
                }
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // Buscador
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar lote o partida...", color = TextHint) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-24).dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = AvicolaPrimary,
                        unfocusedBorderColor = Line
                    ),
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = TextHint) }
                )

                // Filtros (distribución equitativa para que entren las 4 etiquetas)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Todos", "Pendientes", "Activos", "Cerrados").forEach { filterName ->
                        com.digitador.avicola.ui.components.FilterChip(
                            label = filterName,
                            selected = selectedFilter == filterName,
                            onClick = { selectedFilter = filterName },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (ui.error.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        color = Error.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Error.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(ui.error, color = Error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (ui.partidas.isNotEmpty()) {
                    val filteredList = remember(ui.partidas, searchQuery, selectedFilter) {
                        ui.partidas.filter { p ->
                            val matchesSearch = searchQuery.isBlank() ||
                                p.numero.contains(searchQuery, true) ||
                                p.lote.contains(searchQuery, true)
                            val matchesFilter = when (selectedFilter) {
                                "Pendientes" -> p.pendiente
                                "Activos"  -> !p.finalizada && !p.pendiente
                                "Cerrados" -> p.finalizada
                                else       -> true
                            }
                            matchesSearch && matchesFilter
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(items = filteredList, key = { it.id }) { summary ->
                            var showConfirm by remember { mutableStateOf(false) }
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = {
                                    if (it == SwipeToDismissBoxValue.EndToStart) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showConfirm = true
                                    }
                                    false
                                }
                            )

                            if (showConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showConfirm = false },
                                    icon = { Icon(Icons.Default.Delete, null, tint = TextSecondary) },
                                    title = { Text("Borrar partida") },
                                    text = {
                                        val nombre = if (summary.pendiente) "el lote pendiente"
                                            else "la partida ${summary.numero}"
                                        Text("¿Deseas eliminar definitivamente $nombre? Esta acción no se puede deshacer.")
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showConfirm = false
                                            vm.deletePartida(summary.id)
                                        }) { Text("Borrar", color = TextPrimary, fontWeight = FontWeight.Bold) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showConfirm = false }) { Text("Cancelar") }
                                    }
                                )
                            }

                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                backgroundContent = {
                                    val swiping = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(
                                                if (swiping) Error.copy(alpha = 0.8f) else Color.Transparent,
                                                RoundedCornerShape(16.dp)
                                            )
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(Icons.Default.Delete, null, tint = Color.White)
                                    }
                                },
                                content = {
                                    PartidaCard(summary,
                                        onSelect = {
                                            vm.selectPartida(summary.id)
                                            if (summary.pendiente) onCompletePending(summary.id)
                                            else onSelect(summary.id)
                                        },
                                        // Long-press → editar recepción. Solo lotes activos no finalizados.
                                        onLongPress = if (!summary.pendiente && !summary.finalizada) {
                                            { editTarget = summary }
                                        } else null
                                    )
                                }
                            )
                        }
                    }
                } else if (!ui.loading) {
                    EmptyHistoryState()
                }
            }
        }
    }
}

@Composable
private fun PartidaCard(summary: PartidaSummary, onSelect: () -> Unit, onLongPress: (() -> Unit)? = null) {
    if (summary.pendiente) PendingCard(summary, onSelect) else ActiveCard(summary, onSelect, onLongPress)
}

/** Tarjeta de lote ACTIVO (ya digitado completo). Long-press → editar recepción. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActiveCard(summary: PartidaSummary, onSelect: () -> Unit, onLongPress: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Row(
            // El clic va DENTRO de la Card: la Surface recorta el contenido a la forma
            // redondeada, así el ripple/realce respeta las puntas (no se ve cuadrado).
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onSelect, onLongClick = onLongPress)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(54.dp)) {
                CircularProgressIndicator(
                    progress = { summary.progreso / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = if (summary.progreso == 100) OkGreen else Accent,
                    trackColor = Line.copy(alpha = 0.3f),
                    strokeWidth = 4.dp,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        summary.ultimaSemana.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = if (summary.progreso == 100) OkGreen else Ink
                    )
                    Text("SEM", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = TextTertiary)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Partida ${summary.numero}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "${summary.avesActuales} aves vivas",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
            Surface(
                color = (if (summary.progreso == 100) OkGreen else Accent).copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "${summary.progreso}%",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (summary.progreso == 100) OkGreen else Accent
                )
            }
        }
    }
}

/**
 * Tarjeta de lote PENDIENTE (Propuesta A):
 * - 4 dots de progreso arriba (Archivo · Identif · Recepción · Empezar)
 * - Título dinámico (nombre del lote si ya hay, sino fallback)
 * - Subtítulo dinámico según el siguiente paso esperado
 * - Chips de KPIs (aves, peso, parcelas) — los aún no digitados se ven en gris
 */
@Composable
private fun PendingCard(summary: PartidaSummary, onSelect: () -> Unit) {
    val hasIdent = summary.numero.isNotBlank()
    val hasRecep = summary.avesIniciales > 0

    val titulo = if (hasIdent) "Partida ${summary.numero}" else "Sin identificar"
    val subtitulo = when {
        !hasIdent -> "Distribución cargada · falta identificar"
        !hasRecep -> "Esperando recepción"
        else      -> "${summary.avesIniciales} aves · listo para empezar"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = WarningLight),
        border = androidx.compose.foundation.BorderStroke(1.dp, Warning.copy(alpha = 0.5f))
    ) {
        // Clic dentro de la Card para que el ripple respete las esquinas redondeadas.
        Column(modifier = Modifier.fillMaxWidth().clickable { onSelect() }.padding(14.dp)) {

            // ── Fila superior: icono · dots · badge Pendiente ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Warning.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = Warning,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))

                ProgressDots(
                    completed = summary.pasosCompletados,
                    total = 4,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    color = Warning.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Pendiente",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = Warning
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Título y subtítulo ──
            Text(
                titulo,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary
            )
            Text(
                subtitulo,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )

            Spacer(Modifier.height(10.dp))

            // ── Chips de KPIs ──
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KpiChip(
                    icon = Icons.Default.FlutterDash,
                    text = if (hasRecep) "${summary.avesIniciales} aves" else "— aves",
                    active = hasRecep,
                    modifier = Modifier.weight(1f)
                )
                KpiChip(
                    icon = Icons.Default.MonitorWeight,
                    text = if (summary.pesoInicialTotal > 0)
                        String.format(java.util.Locale.US, "%.1f kg", summary.pesoInicialTotal / 1000.0)
                        else "— kg",
                    active = summary.pesoInicialTotal > 0,
                    modifier = Modifier.weight(1f)
                )
                KpiChip(
                    icon = Icons.Default.GridView,
                    text = "${summary.parcelasTotal} parc.",
                    active = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Indicador de pasos: dots rellenos para pasos completados, vacíos para pendientes. */
@Composable
private fun ProgressDots(
    completed: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            val done = i < completed
            Box(
                modifier = Modifier
                    .size(if (done) 9.dp else 8.dp)
                    .background(
                        if (done) AvicolaPrimary else Warning.copy(alpha = 0.25f),
                        CircleShape
                    )
            )
        }
    }
}

/** Chip de KPI: ícono + texto; opacidad reducida si aún no hay dato. */
@Composable
private fun KpiChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = if (active) Color.White else Color.White.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (active) Warning.copy(alpha = 0.35f) else Warning.copy(alpha = 0.18f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = if (active) Warning else TextHint
            )
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (active) TextPrimary else TextHint,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun EmptyHistoryState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.FileOpen, null, tint = TextHint, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "Todavía no hay lotes",
            color = TextSecondary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Carga un archivo .davi o recibe uno por WhatsApp para empezar.",
            color = TextTertiary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

