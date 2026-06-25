package com.digitador.avicola.ui.screen.papelera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitador.avicola.domain.PapeleraItem
import com.digitador.avicola.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PapeleraScreen(
    onBack: () -> Unit,
    vm: PapeleraViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { vm.cargar() }
    val ui by vm.ui.collectAsState()

    var confirmarBorrar by remember { mutableStateOf<PapeleraItem?>(null) }

    confirmarBorrar?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmarBorrar = null },
            icon = { Icon(Icons.Default.DeleteForever, null, tint = Error) },
            title = { Text("Borrar definitivamente") },
            text = { Text("El lote (partida ${item.numero}) se eliminará para siempre. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.borrarDefinitivo(item.id)
                    confirmarBorrar = null
                }) { Text("Borrar para siempre", color = Error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmarBorrar = null }) { Text("Cancelar") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Papelera", style = MaterialTheme.typography.titleLarge, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AvicolaPrimary)
            )
        },
        containerColor = Background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // Aviso de retención
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                color = Warning.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Warning.copy(alpha = 0.3f))
            ) {
                Text(
                    "Los lotes eliminados se borran definitivamente a los 15 días. Podés restaurarlos antes de que venza el plazo.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = WarningDark,
                    fontWeight = FontWeight.Medium
                )
            }

            if (ui.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AvicolaPrimary)
                }
            } else if (ui.items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.DeleteOutline, null, tint = TextHint, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("La papelera está vacía", color = TextSecondary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(ui.items, key = { it.id }) { item ->
                        PapeleraCard(
                            item = item,
                            onRestaurar = { vm.restaurar(item.id) },
                            onBorrar = { confirmarBorrar = item }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PapeleraCard(
    item: PapeleraItem,
    onRestaurar: () -> Unit,
    onBorrar: () -> Unit
) {
    val urgente = item.diasRestantes <= 3
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(44.dp).background(SurfaceMuted, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.DeleteOutline, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (item.numero.isNotBlank()) "Partida ${item.numero}" else "Lote sin identificar",
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
                Text(
                    "Se borra en ${item.diasRestantes} día${if (item.diasRestantes == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (urgente) Error else TextTertiary
                )
            }
            // Restaurar
            IconButton(onClick = onRestaurar) {
                Icon(Icons.Default.RestoreFromTrash, "Restaurar", tint = AvicolaPrimary)
            }
            // Borrar definitivo
            IconButton(onClick = onBorrar) {
                Icon(Icons.Default.DeleteForever, "Borrar definitivo", tint = Error)
            }
        }
    }
}
