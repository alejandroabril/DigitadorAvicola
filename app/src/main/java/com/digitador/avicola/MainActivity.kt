package com.digitador.avicola

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.digitador.avicola.ui.navigation.DigitadorNavGraph
import com.digitador.avicola.ui.navigation.Screen
import com.digitador.avicola.ui.theme.AvicolaPrimary
import com.digitador.avicola.ui.theme.Background
import com.digitador.avicola.ui.theme.SurfaceAlt
import com.digitador.avicola.ui.theme.DigitadorTheme
import com.digitador.avicola.data.repository.DigitadorRepository
import com.digitador.avicola.data.repository.ExportService
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Bus simple para señalar qué hacer tras abrir un archivo .davi:
 *  - [newPendingPartidaId]: se creó un lote PENDIENTE (solo distribución) → ir al asistente.
 *  - [loadedPartidaId]: se restauró un lote COMPLETO (backup) → ir directo al seguimiento. */
object ImportBus {
    var newPendingPartidaId by mutableStateOf<Long?>(null)
    var loadedPartidaId by mutableStateOf<Long?>(null)
    /** Backup de un lote que YA existe (mismo UID): la UI pregunta si guardar copia. */
    var duplicateBackupJson by mutableStateOf<String?>(null)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var repo: DigitadorRepository
    @Inject lateinit var exportService: ExportService

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Hacemos que la splash screen nativa espere un poco para que nuestra animación empiece
        // justo cuando ella desaparece.
        splashScreen.setKeepOnScreenCondition { false }

        enableEdgeToEdge()
        handleIncoming(intent)

        setContent {
            DigitadorTheme {
                var showMainContent by remember { mutableStateOf(false) }

                Crossfade(
                    targetState = showMainContent,
                    animationSpec = tween(800, easing = EaseInOutQuart),
                    label = "main_transition"
                ) { targetState ->
                    if (targetState) {
                        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                            val navController = rememberNavController()
                            DigitadorNavGraph(navController = navController)
                            // Lote PENDIENTE (solo distribución) → asistente de completado.
                            LaunchedEffect(ImportBus.newPendingPartidaId) {
                                val id = ImportBus.newPendingPartidaId
                                if (id != null) {
                                    repo.setCurrentPartida(id)
                                    kotlinx.coroutines.yield()
                                    navController.navigate(Screen.Setup())
                                    ImportBus.newPendingPartidaId = null
                                }
                            }
                            // Lote COMPLETO restaurado desde un backup → seguimiento directo.
                            LaunchedEffect(ImportBus.loadedPartidaId) {
                                val id = ImportBus.loadedPartidaId
                                if (id != null) {
                                    repo.setCurrentPartida(id)
                                    kotlinx.coroutines.yield()
                                    navController.navigate(Screen.Main(1)) {
                                        popUpTo(Screen.History) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                    ImportBus.loadedPartidaId = null
                                }
                            }

                            // Backup de un lote YA cargado → preguntar si guardar copia.
                            ImportBus.duplicateBackupJson?.let { dupJson ->
                                AlertDialog(
                                    onDismissRequest = { ImportBus.duplicateBackupJson = null },
                                    title = { Text("Este lote ya está cargado") },
                                    text = { Text("Ya existe este lote en la app. ¿Querés guardar una copia con número distinto?") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            ImportBus.duplicateBackupJson = null
                                            importarBackupComoCopia(dupJson)
                                        }) { Text("Guardar como copia") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { ImportBus.duplicateBackupJson = null }) { Text("Cancelar") }
                                    }
                                )
                            }
                        }
                    } else {
                        SynchronizedSplashScreen(onFinished = { showMainContent = true })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        // IMPORTANTE: leer el archivo y parsear el JSON FUERA del hilo principal.
        // Antes corría síncrono en onCreate → bloqueaba el arranque (la app y los
        // iconos tardaban en aparecer y todo se sentía lento unos segundos).
        // El permiso content:// sigue válido mientras la Activity tenga el intent,
        // así que leer dentro de la corrutina (que arranca de inmediato) es seguro.
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    e.printStackTrace(); null
                }
            }
            if (json.isNullOrBlank()) {
                toast("No se pudo leer el archivo")
                return@launch
            }

            try {
                val esBackup = withContext(Dispatchers.Default) { exportService.esBackupCompleto(json) }
                if (esBackup) {
                    // Backup completo. Si el lote (UID) ya existe → pedir decisión a la UI.
                    val uid = withContext(Dispatchers.Default) { exportService.uidDeBackup(json) }
                    if (uid.isNotBlank() && repo.existePartidaConUid(uid)) {
                        ImportBus.duplicateBackupJson = json
                    } else {
                        // importarDesdeJson ya trabaja en Dispatchers.IO internamente.
                        exportService.importarDesdeJson(json).getOrNull()?.let { newId ->
                            ImportBus.loadedPartidaId = newId
                        } ?: toast("No se pudo importar el lote (archivo inválido)")
                    }
                } else {
                    // Solo distribución → crear lote pendiente y abrir el asistente.
                    val dist = withContext(Dispatchers.Default) { parseDistribucion(json) }
                    if (dist.isEmpty()) {
                        toast("El archivo no contiene una distribución válida")
                        return@launch
                    }
                    val newId = repo.crearPartidaPendiente(dist)
                    ImportBus.newPendingPartidaId = newId
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toast("Archivo .davi inválido o dañado")
            }
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Importa el backup como copia (UID nuevo + número con sufijo) y abre el lote. */
    private fun importarBackupComoCopia(json: String) {
        lifecycleScope.launch {
            exportService.importarDesdeJson(json, comoCopia = true).getOrNull()?.let { newId ->
                ImportBus.loadedPartidaId = newId
            }
        }
    }

    /** Acepta los dos formatos: distribución pura {G1:{T1:[...]}} o estado completo con "galeras". */
    private fun parseDistribucion(json: String): Map<String, Map<String, List<String>>> {
        val gson = Gson()
        val element = JsonParser.parseString(json)
        return if (element.isJsonObject && element.asJsonObject.has("galeras")) {
            // Parseo DEFENSIVO: el intent puede traer cualquier JSON (octet-stream).
            // Si algo no calza, se omite ese nodo en vez de lanzar excepción.
            val full = element.asJsonObject.getAsJsonArray("galeras") ?: return emptyMap()
            val out = linkedMapOf<String, MutableMap<String, List<String>>>()
            full.forEach { gEl ->
                if (!gEl.isJsonObject) return@forEach
                val g = gEl.asJsonObject
                val gId = g.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                val corrales = g.getAsJsonArray("corrales") ?: return@forEach
                val cMap = linkedMapOf<String, List<String>>()
                corrales.forEach { cEl ->
                    if (!cEl.isJsonObject) return@forEach
                    val c = cEl.asJsonObject
                    val tLabel = c.get("id")?.takeIf { !it.isJsonNull }?.asString
                        ?.substringAfterLast("-") ?: return@forEach
                    val parcelas = (c.getAsJsonArray("parcelas") ?: return@forEach).mapNotNull { pEl ->
                        if (pEl.isJsonObject) pEl.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString else null
                    }
                    cMap[tLabel] = parcelas
                }
                out[gId] = cMap
            }
            out.mapValues { it.value.toMap() }
        } else {
            try {
                val type = object : TypeToken<Map<String, Map<String, List<String>>>>() {}.type
                gson.fromJson<Map<String, Map<String, List<String>>>>(json, type) ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
}

@Composable
fun SynchronizedSplashScreen(onFinished: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    
    // Estados de animación
    // Iniciamos en 0.5f para simular que viene del tamaño del icono del launcher
    val scaleX = remember { Animatable(0.5f) }
    val scaleY = remember { Animatable(0.5f) }
    val offsetY = remember { Animatable(0f) }
    val rotation = remember { Animatable(0f) }
    val alphaBackground = remember { Animatable(0f) }
    
    val glowAlpha = remember { Animatable(0f) }
    val glowScale = remember { Animatable(0.7f) }
    
    val textAlpha = remember { Animatable(0f) }
    val textScale = remember { Animatable(0.85f) }

    LaunchedEffect(Unit) {
        // --- PASO 1: EXPANSIÓN DESDE EL ICONO ---
        // Transición fluida del blanco puro al gradiente de fondo
        launch { alphaBackground.animateTo(1f, tween(400)) }
        
        // El icono se expande a su tamaño normal (1f) con un rebote suave
        launch {
            scaleX.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) 
        }
        launch { 
            scaleY.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) 
        }
        
        delay(250) // Pausa mínima para conectar visualmente

        // --- PASO 2: COREOGRAFÍA DE SALTO ---
        launch {
            // Squash & Stretch
            launch {
                scaleX.animateTo(1.25f, tween(150, easing = FastOutSlowInEasing))
                scaleX.animateTo(0.8f, tween(120))
                scaleX.animateTo(1f, spring(Spring.DampingRatioMediumBouncy))
            }
            launch {
                scaleY.animateTo(0.65f, tween(150, easing = FastOutSlowInEasing))
                scaleY.animateTo(1.35f, tween(120))
                scaleY.animateTo(1f, spring(Spring.DampingRatioMediumBouncy))
            }

            // Salto
            offsetY.animateTo(-180f, tween(450, easing = EaseOutQuad))
            
            // Aterrizaje
            offsetY.animateTo(0f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

            // Aurora Fluida
            launch { glowAlpha.animateTo(0.7f, tween(1000, easing = EaseOutCubic)) }
            launch { glowScale.animateTo(1.15f, tween(1200, easing = EaseOutBack)) }
        }
        
        launch {
            delay(150)
            rotation.animateTo(20f, tween(250, easing = EaseOutQuad))
            rotation.animateTo(-10f, tween(200))
            rotation.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
        }

        // --- REVELACIÓN DEL TÍTULO ---
        launch {
            delay(350)
            launch { textAlpha.animateTo(1f, tween(1000, easing = EaseOutQuart)) }
            launch { textScale.animateTo(1f, tween(1200, easing = EaseOutQuart)) }
        }

        delay(2000)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White) // Capa base blanca para coincidir con el splash nativo
    ) {
        // Fondo en gradiente que aparece suavemente
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(alphaBackground.value)
                .background(Brush.verticalGradient(listOf(Color.White, SurfaceAlt)))
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .offset(y = offsetY.value.dp)
                    .graphicsLayer {
                        this.scaleX = scaleX.value
                        this.scaleY = scaleY.value
                        this.rotationZ = rotation.value
                    },
                contentAlignment = Alignment.Center
            ) {
                // Aurora
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .scale(glowScale.value)
                        .alpha(glowAlpha.value)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(AvicolaPrimary.copy(alpha = 0.5f), Color.Transparent),
                            ),
                            shape = CircleShape
                        )
                )

                // Pollito
                Image(
                    painter = painterResource(id = R.drawable.pollo),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .size(200.dp)
                        .border(2.dp, AvicolaPrimary.copy(alpha = 0.3f), RoundedCornerShape(40.dp))
                        .clip(RoundedCornerShape(40.dp))
                )
            }
            
            Spacer(Modifier.height(45.dp))
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .graphicsLayer {
                        this.alpha = textAlpha.value
                        this.scaleX = textScale.value
                        this.scaleY = textScale.value
                    }
            ) {
                Text(
                    text = "FLOCK TRACKER",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AvicolaPrimary,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp
                )
                Text(
                    text = "Smart Poultry Management",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
