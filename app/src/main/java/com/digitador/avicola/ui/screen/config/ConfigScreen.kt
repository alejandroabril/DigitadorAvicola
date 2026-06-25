package com.digitador.avicola.ui.screen.config

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitador.avicola.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onBack: () -> Unit,
    vm: ConfigViewModel = hiltViewModel()
) {
    val pinOn by vm.pinHabilitado.collectAsState()
    var showCambiarPin by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var compartiendo by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Configuración", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AvicolaPrimary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── SEGURIDAD ──
            SectionTitle("Seguridad")
            ConfigCard {
                // PIN de bloqueo (on/off)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, null, tint = AvicolaPrimary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("PIN de bloqueo", fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text(
                            "Exigir PIN para la papelera y el desbloqueo de semanas",
                            style = MaterialTheme.typography.labelSmall, color = TextTertiary
                        )
                    }
                    Switch(checked = pinOn, onCheckedChange = { vm.setPinHabilitado(it) })
                }

                HorizontalDivider(color = Line.copy(alpha = 0.4f))

                // Cambiar PIN
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = pinOn) { showCambiarPin = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Edit, null, tint = if (pinOn) AvicolaPrimary else TextTertiary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Cambiar PIN", fontWeight = FontWeight.Medium, color = if (pinOn) TextPrimary else TextTertiary)
                        Text(
                            if (pinOn) "Actualizá el código de 4 dígitos" else "Activá el PIN para poder cambiarlo",
                            style = MaterialTheme.typography.labelSmall, color = TextTertiary
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TextTertiary)
                }
            }

            // ── COMPARTIR ──
            SectionTitle("Compartir")
            ConfigCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !compartiendo) {
                            compartiendo = true
                            scope.launch {
                                try {
                                    val apk = vm.exportService.compartirApk(context)
                                    vm.exportService.shareFile(context, apk)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    compartiendo = false
                                }
                            }
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Share, null, tint = AvicolaPrimary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Compartir app (APK)", fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text(
                            "Enviá el instalador a otra persona (WhatsApp, Bluetooth, Drive…)",
                            style = MaterialTheme.typography.labelSmall, color = TextTertiary
                        )
                    }
                    if (compartiendo) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AvicolaPrimary)
                    } else {
                        Icon(Icons.Default.ChevronRight, null, tint = TextTertiary)
                    }
                }
            }

            // ── PRÓXIMAMENTE ──
            SectionTitle("Próximamente")
            ConfigCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Update, null, tint = TextTertiary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Más opciones de configuración en próximas actualizaciones",
                        style = MaterialTheme.typography.bodyMedium, color = TextTertiary
                    )
                }
            }
        }
    }

    if (showCambiarPin) {
        CambiarPinDialog(
            onDismiss = { showCambiarPin = false },
            onConfirm = { actual, nuevo -> vm.cambiarPin(actual, nuevo) }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = TextTertiary,
        fontWeight = FontWeight.Black,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun ConfigCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Border)
    ) {
        Column(content = content)
    }
}

@Composable
private fun CambiarPinDialog(
    onDismiss: () -> Unit,
    onConfirm: (actual: String, nuevo: String) -> Boolean
) {
    var actual by remember { mutableStateOf("") }
    var nuevo by remember { mutableStateOf("") }
    var conf by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun soloDigitos(s: String) = s.length <= 4 && s.all { it.isDigit() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cambiar PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PinField("PIN actual", actual) { if (soloDigitos(it)) { actual = it; error = null } }
                PinField("Nuevo PIN (4 dígitos)", nuevo) { if (soloDigitos(it)) { nuevo = it; error = null } }
                PinField("Confirmar nuevo PIN", conf) { if (soloDigitos(it)) { conf = it; error = null } }
                error?.let { Text(it, color = Error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    nuevo.length != 4 -> error = "El nuevo PIN debe tener 4 dígitos"
                    nuevo != conf -> error = "Los PIN nuevos no coinciden"
                    !onConfirm(actual, nuevo) -> error = "El PIN actual es incorrecto"
                    else -> onDismiss()
                }
            }) { Text("Guardar", color = AvicolaPrimary, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) }
        }
    )
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AvicolaPrimary),
        modifier = Modifier.fillMaxWidth()
    )
}
