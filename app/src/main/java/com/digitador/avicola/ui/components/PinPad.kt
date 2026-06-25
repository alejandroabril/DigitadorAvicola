package com.digitador.avicola.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.digitador.avicola.ui.theme.*

/**
 * Pantalla completa de PIN con teclado numérico (estilo marcador), colores de la app.
 * Llama [onSuccess] cuando [onVerify] devuelve true para el PIN ingresado. El valor real
 * nunca se expone a la UI: la verificación ocurre contra el hash en el repositorio.
 */
@Composable
fun PinDialog(
    onVerify: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    titulo: String = "Ingresá el PIN"
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current

        fun validar(p: String) {
            if (onVerify(p)) onSuccess()
            else { error = true; pin = "" }
        }
        fun pulsar(d: String) {
            if (pin.length >= 4) return
            error = false
            pin += d
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (pin.length == 4) validar(pin)
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar", color = AvicolaPrimary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(48.dp))
                }

                Spacer(Modifier.height(24.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Lock, null, tint = AvicolaPrimary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (error) "PIN incorrecto" else titulo,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (error) Error else TextPrimary
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        repeat(4) { i ->
                            val filled = i < pin.length
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .border(
                                        width = 2.dp,
                                        color = if (error) Error else if (filled) AvicolaPrimary else BorderStrong,
                                        shape = CircleShape
                                    )
                                    .background(
                                        color = if (filled) AvicolaPrimary else Color.Transparent,
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                val filas = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("", "0", "del")
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    filas.forEach { fila ->
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            fila.forEach { key ->
                                when (key) {
                                    "" -> Spacer(Modifier.size(76.dp))
                                    "del" -> PinKey(onClick = {
                                        if (pin.isNotEmpty()) { pin = pin.dropLast(1); error = false }
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.Backspace, "Eliminar", tint = TextSecondary, modifier = Modifier.size(26.dp))
                                    }
                                    else -> PinKey(onClick = { pulsar(key) }) {
                                        Text(key, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PinKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        shadowElevation = 1.dp,
        modifier = Modifier.size(76.dp)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
