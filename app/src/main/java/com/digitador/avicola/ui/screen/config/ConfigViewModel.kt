package com.digitador.avicola.ui.screen.config

import androidx.lifecycle.ViewModel
import com.digitador.avicola.data.repository.ConfigRepository
import com.digitador.avicola.data.repository.ExportService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val config: ConfigRepository,
    val exportService: ExportService
) : ViewModel() {

    val pinHabilitado: StateFlow<Boolean> = config.pinHabilitado

    fun setPinHabilitado(activo: Boolean) = config.setPinHabilitado(activo)

    /** Devuelve true si el cambio fue válido (PIN actual correcto y nuevo de 4 dígitos). */
    fun cambiarPin(actual: String, nuevo: String): Boolean = config.cambiarPin(actual, nuevo)
}
