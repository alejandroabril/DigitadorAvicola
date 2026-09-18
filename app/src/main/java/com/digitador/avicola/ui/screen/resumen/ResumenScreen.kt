package com.digitador.avicola.ui.screen.resumen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitador.avicola.data.repository.AppState
import com.digitador.avicola.domain.*
import com.digitador.avicola.ui.components.*
import com.digitador.avicola.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumenScreen(
    semanaNumero: Int,
    onBack: () -> Unit,
    onNavSemana: (Int) -> Unit,
    vm: ResumenViewModel = hiltViewModel()
) {
    LaunchedEffect(semanaNumero) { vm.cargar(semanaNumero) }

    val ui      by vm.ui.collectAsState()
    val st      = ui.appState
    val semPad  = semanaNumero.toString().padStart(2, '0')
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportingPdf by remember { mutableStateOf(false) }
    // Métricas que NO están en la planilla del ensayo (FEP, FCR ajustado, CV%).
    // Por defecto OFF → el Excel exportado coincide 1:1 con la planilla.
    var incluirExtras by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Análisis de la semana", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text("Semana $semPad", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White)
                    }
                },
                actions = {
                    val prev = semanaNumero - 1
                    val next = semanaNumero + 1
                    val maxSem = st.semanas.maxOfOrNull { it.numero } ?: 1
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (prev >= 1) onNavSemana(prev) }, enabled = prev >= 1) {
                            Icon(Icons.Default.ChevronLeft, "Semana anterior", tint = Color.White)
                        }
                        IconButton(onClick = { if (next <= maxSem) onNavSemana(next) }, enabled = next <= maxSem) {
                            Icon(Icons.Default.ChevronRight, "Semana siguiente", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AvicolaPrimary)
            )
        },
        containerColor = Background
    ) { padding ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AvicolaPrimary)
            }
            return@Scaffold
        }

        val partida = st.partida ?: return@Scaffold

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── KPIs DEL LOTE (GLOBAL) ──
            item {
                GlobalKpiCard(ui.global)
            }

            // ── LISTADO UNIFICADO POR GALERA ──
            partida.galeras.forEach { galera ->
                item {
                    AppPanel(title = galera.nombre) {
                        Column {
                            // Cabecera de la tabla
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Trat.", Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                                Text("Cons.Sem", Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                                Text("Peso", Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                                Text("FCR", Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                                Text("Mort%", Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                            }

                            galera.corrales.forEach { corral ->
                                val metricas = ui.metricasPorCorral[corral.id]
                                CorralAnalysisRow(corral, metricas)
                                HorizontalDivider(color = Line.copy(alpha = 0.3f), thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }

            // ── ACCIONES ──
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Interruptor: incluir métricas adicionales (no presentes en la planilla)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Métricas adicionales",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                "Columnas de FCR ajustado a 2.0/2.5/2.7 kg (no están en la planilla)",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = incluirExtras, onCheckedChange = { incluirExtras = it })
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                exporting = true
                                val opts = OpcionesExport(
                                    incluirFep = incluirExtras,
                                    incluirFcrAjustado = incluirExtras,
                                    incluirCv = incluirExtras
                                )
                                scope.launch {
                                    try {
                                        val file = vm.exportService.exportarExcel(context, opts)
                                        val num = st.partida?.numero?.takeIf { it.isNotBlank() } ?: "—"
                                        val loteTxt = st.partida?.lote?.takeIf { it.isNotBlank() }?.let { " · Lote $it" } ?: ""
                                        vm.exportService.shareFile(
                                            context, file,
                                            asunto = "Estadística avícola — Partida $num$loteTxt",
                                            mensaje = "Adjunto el reporte de estadística del lote $num$loteTxt.\n\n" +
                                                "Contiene los indicadores por parcela y semana (peso, consumo, FCR, GDP, mortalidad).\n\n" +
                                                "— Enviado desde Flock Tracker"
                                        )
                                    } finally { exporting = false }
                                }
                            },
                            enabled = !exporting,
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AvicolaPrimary)
                        ) {
                            Icon(Icons.Default.FileDownload, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (exporting) "Generando…" else "Excel")
                        }
                        OutlinedButton(
                            onClick = {
                                exportingPdf = true
                                val opts = OpcionesExport(
                                    incluirFep = incluirExtras,
                                    incluirFcrAjustado = incluirExtras,
                                    incluirCv = incluirExtras
                                )
                                scope.launch {
                                    try {
                                        val file = vm.exportService.exportarPdfResumen(context, semanaNumero, opts)
                                        val num = st.partida?.numero?.takeIf { it.isNotBlank() } ?: "—"
                                        val loteTxt = st.partida?.lote?.takeIf { it.isNotBlank() }?.let { " · Lote $it" } ?: ""
                                        vm.exportService.shareFile(
                                            context, file,
                                            asunto = "Análisis semana $semPad — Partida $num$loteTxt",
                                            mensaje = "Adjunto el análisis de la semana $semPad del lote $num$loteTxt.\n\n" +
                                                "— Enviado desde Flock Tracker"
                                        )
                                    } finally { exportingPdf = false }
                                }
                            },
                            enabled = !exportingPdf,
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AvicolaPrimary)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (exportingPdf) "Generando…" else "PDF")
                        }
                    }
                }
            }
            
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun GlobalKpiCard(global: KpiGlobal) {
    AppPanel(title = "Rendimiento Global") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            KpiItem(
                "Saldo Aves",
                String.format(Locale.US, "%,d", global.saldo).replace(',', '.'),
                Modifier.weight(1f)
            )
            KpiItem(
                "Peso Prom.",
                if (global.pesoProm > 0) String.format(Locale.US, "%.0fg", global.pesoProm) else "—",
                Modifier.weight(1f)
            )
            KpiItem(
                "FCR Sem.",
                if (global.fcrSem > 0) String.format(Locale.US, "%.3f", global.fcrSem) else "—",
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun KpiItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = AvicolaPrimary)
    }
}

@Composable
private fun CorralAnalysisRow(corral: Corral, metricas: MetricasCorral?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ID del Corral en pequeño badge
        Surface(
            color = AvicolaPrimary,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.size(width = 42.dp, height = 30.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    corral.id.split("-").last(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            metricas?.let { String.format(Locale.US, "%.1f", it.consumoGave) } ?: "—",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            metricas?.let { String.format(Locale.US, "%.0fg", it.promPeso) } ?: "—",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Accent
        )
        Text(
            metricas?.fcrAcum?.let { String.format(Locale.US, "%.3f", it) } ?: "—",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            metricas?.let { String.format(Locale.US, "%.1f%%", it.mortAcumPct * 100) } ?: "—",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if ((metricas?.mortAcumPct ?: 0.0) > 0.05) Error else TextPrimary
        )
    }
}
