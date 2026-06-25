package com.digitador.avicola.ui.screen.ajustes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitador.avicola.ui.components.*
import com.digitador.avicola.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AjustesScreen(
    onBack: () -> Unit,
    onPartidaCerrada: () -> Unit,
    vm: AjustesViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { vm.cargar() }
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Versión real del APK instalado (refleja el versionName de build.gradle.kts).
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }

    var showCerrarDialog by remember { mutableStateOf(false) }

    LaunchedEffect(ui.mensaje) {
        if (ui.mensaje.isNotBlank()) {
            snackbarHostState.showSnackbar(ui.mensaje)
            vm.limpiarMensaje()
        }
    }

    if (showCerrarDialog) {
        AlertDialog(
            onDismissRequest = { showCerrarDialog = false },
            title = { Text("¿Cerrar este lote?") },
            text = { Text("El lote quedará marcado como finalizado. Podrás verlo en el historial pero no editarlo.") },
            confirmButton = {
                TextButton(onClick = {
                    showCerrarDialog = false
                    vm.cerrarPartida(onPartidaCerrada)
                }) { Text("Cerrar lote", fontWeight = FontWeight.Bold, color = TextPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showCerrarDialog = false }) { Text("Cancelar") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes", style = MaterialTheme.typography.titleLarge, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AvicolaPrimary)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Background
    ) { padding ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AvicolaPrimary)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Info de la partida (solo lectura) ──
            ui.partida?.let { p ->
                AppPanel(title = "Lote actual") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // weight(1f): esta columna flexiona y envuelve el texto largo
                            // del lote, dejando que la fecha conserve su ancho natural.
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Partida ${p.numero}", fontWeight = FontWeight.Bold)
                                Text("Lote: ${p.lote.ifBlank { "—" }}", color = TextSecondary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Fecha Inicio", fontSize = 10.sp, color = TextTertiary)
                                Text(
                                    p.fechaInicio.ifBlank { "—" },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (p.finalizada) {
                            Spacer(Modifier.height(4.dp))
                            AlertBanner("Este lote está cerrado.", AlertType.WARN)
                        }
                    }
                }
            }

            // ── Exportar lote (.davi) ──
            ActionTile(
                icon = Icons.Default.Share,
                title = "Exportar lote",
                subtitle = "Genera un archivo que abre y carga el lote en otro equipo",
                enabled = !ui.procesando,
                onClick = { vm.exportarLote(context) }
            )

            // ── Cerrar partida ──
            if (ui.partida?.finalizada == false) {
                ActionTile(
                    icon = Icons.Default.Lock,
                    title = "Cerrar este lote",
                    subtitle = "Lo archiva como finalizado",
                    danger = true,
                    onClick = { showCerrarDialog = true }
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Versión de la app ──
            Text(
                text = "Flock Tracker · v${versionName ?: "—"}",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = TextTertiary
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (danger) TextSecondary else AvicolaPrimary
    val bgIcon = if (danger) SurfaceMuted else AccentSoft

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(bgIcon, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = if (danger) DangerRed else TextPrimary)
                Text(subtitle, fontSize = 12.sp, color = TextTertiary)
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextHint)
        }
    }
}
