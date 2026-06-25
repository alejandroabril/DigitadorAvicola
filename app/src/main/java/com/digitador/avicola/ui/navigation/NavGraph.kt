package com.digitador.avicola.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.digitador.avicola.ui.screen.ajustes.AjustesScreen
import com.digitador.avicola.ui.screen.config.ConfigScreen
import com.digitador.avicola.ui.screen.history.HistoryScreen
import com.digitador.avicola.ui.screen.digitacion.DigitacionCategory
import com.digitador.avicola.ui.screen.digitacion.DigitacionScreen
import com.digitador.avicola.ui.screen.resumen.ResumenScreen
import com.digitador.avicola.ui.screen.semana.SemanaScreen
import com.digitador.avicola.ui.screen.setup.SetupScreen
import kotlinx.serialization.Serializable

@Serializable sealed interface Screen {
    @Serializable data object History : Screen
    /**
     * Asistente de Setup. [startStep] = 0 → usa el paso que calcula el ViewModel
     * (completar un pendiente). [startStep] > 0 → arranca forzado en ese paso
     * (editar un lote ya activo: 2 = Recepción).
     */
    @Serializable data class Setup(val startStep: Int = 0) : Screen
    @Serializable data object Papelera : Screen
    @Serializable data object Config : Screen
    @Serializable data class Main(val semana: Int) : Screen
    @Serializable data class Ingreso(
        val semana: Int,
        val galera: String,
        val categoria: String,
        val grupo: String
    ) : Screen
    @Serializable data class Resumen(val semana: Int) : Screen
    @Serializable data object Ajustes : Screen
}

@Composable
fun DigitadorNavGraph(navController: NavHostController) {
    // Transiciones más cortas (250 ms) para que la navegación se sienta ágil.
    val dur = 250
    NavHost(
        navController = navController,
        startDestination = Screen.History,
        enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(dur)) + fadeIn(animationSpec = tween(dur)) },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(dur)) + fadeOut(animationSpec = tween(dur)) },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(dur)) + fadeIn(animationSpec = tween(dur)) },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(dur)) + fadeOut(animationSpec = tween(dur)) }
    ) {

        composable<Screen.History> {
            HistoryScreen(
                onSelect = { _ ->
                    navController.navigate(Screen.Main(1)) {
                        popUpTo(Screen.History) { inclusive = false }
                    }
                },
                onCompletePending = { _ ->
                    // Abrir el asistente para completar Identificación + Recepción.
                    navController.navigate(Screen.Setup())
                },
                onEditarActivo = { _ ->
                    // Editar un lote ya activo: abre el asistente directo en Recepción.
                    navController.navigate(Screen.Setup(startStep = 2))
                },
                onOpenPapelera = { navController.navigate(Screen.Papelera) },
                onOpenConfig = { navController.navigate(Screen.Config) }
            )
        }

        composable<Screen.Papelera> {
            com.digitador.avicola.ui.screen.papelera.PapeleraScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable<Screen.Config> {
            ConfigScreen(onBack = { navController.popBackStack() })
        }

        // Asistente de Setup. Sirve para DOS casos:
        //  · Completar un lote PENDIENTE (.davi) → startStep = 0, al terminar abre Main(1).
        //  · Editar un lote ACTIVO → startStep > 0, al terminar vuelve al historial.
        composable<Screen.Setup> { back ->
            val args = back.toRoute<Screen.Setup>()
            val editandoActivo = args.startStep > 0
            SetupScreen(
                editMode = true,
                startStep = args.startStep,
                onDone = { _ ->
                    if (editandoActivo) {
                        // Editar recepción: no abrimos digitación, volvemos al historial.
                        navController.popBackStack(Screen.History, inclusive = false)
                    } else {
                        // Completar pendiente: limpia el stack hasta History y abre Main(1).
                        navController.navigate(Screen.Main(1)) {
                            popUpTo(Screen.History) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable<Screen.Main> { back ->
            val args = back.toRoute<Screen.Main>()
            SemanaScreen(
                semanaNumero = args.semana,
                onIngreso = { s, g, cat, group ->
                    navController.navigate(Screen.Ingreso(s, g, cat.name, group))
                },
                onResumen = { s -> navController.navigate(Screen.Resumen(s)) },
                onAjustes = { navController.navigate(Screen.Ajustes) },
                onReset   = { 
                    navController.navigate(Screen.History) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable<Screen.Ingreso> { back ->
            val args = back.toRoute<Screen.Ingreso>()
            val category = try { 
                DigitacionCategory.valueOf(args.categoria) 
            } catch(e: Exception) { 
                DigitacionCategory.MORTALIDAD 
            }
            
            DigitacionScreen(
                semanaNumero = args.semana,
                galeraId     = args.galera,
                initialCategory = category,
                initialGroup = args.grupo,
                onBack       = { navController.popBackStack() }
            )
        }

        composable<Screen.Resumen> { back ->
            val args = back.toRoute<Screen.Resumen>()
            ResumenScreen(
                semanaNumero = args.semana,
                onBack       = { navController.popBackStack() },
                onNavSemana  = { n ->
                    navController.navigate(Screen.Resumen(n)) {
                        popUpTo(Screen.Resumen(args.semana)) { inclusive = true }
                    }
                }
            )
        }

        composable<Screen.Ajustes> {
            AjustesScreen(
                onBack          = { navController.popBackStack() },
                onPartidaCerrada = {
                    navController.navigate(Screen.History) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
