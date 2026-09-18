package com.digitador.avicola.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferencias de configuración de la app (SharedPreferences). No toca la base de datos.
 * Por ahora administra el PIN de bloqueo (habilitado / valor); pensado para crecer con
 * futuras opciones de configuración.
 */
@Singleton
class ConfigRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("config_app", Context.MODE_PRIVATE)

    private val _pinHabilitado = MutableStateFlow(prefs.getBoolean(KEY_PIN_ON, true))
    /** true = se exige PIN para acciones protegidas (papelera, desbloqueo de semana). */
    val pinHabilitado: StateFlow<Boolean> = _pinHabilitado.asStateFlow()

    private val _modoPorTratamiento = MutableStateFlow(prefs.getBoolean(KEY_MODO_TRAT, false))
    /** Modo de digitación: false = por línea (A/B/C/D) · true = por tratamiento (K1, K2…). */
    val modoPorTratamiento: StateFlow<Boolean> = _modoPorTratamiento.asStateFlow()

    fun setModoPorTratamiento(activo: Boolean) {
        prefs.edit().putBoolean(KEY_MODO_TRAT, activo).apply()
        _modoPorTratamiento.value = activo
    }

    /** Recuerda la última semana vista de cada lote (por uid) para reabrir ahí. */
    fun setUltimaSemana(uid: String, semana: Int) {
        if (uid.isBlank()) return
        prefs.edit().putInt(KEY_ULT_SEM + uid, semana).apply()
    }

    /** Última semana vista del lote; 0 si no hay registro. */
    fun getUltimaSemana(uid: String): Int =
        if (uid.isBlank()) 0 else prefs.getInt(KEY_ULT_SEM + uid, 0)

    /** Referencias de alimento excluidas del cálculo de indicadores (por lote y semana). */
    fun refsExcluidas(uid: String, sem: Int): Set<String> =
        if (uid.isBlank()) emptySet()
        else prefs.getStringSet(KEY_REF_EXCL + uid + "_" + sem, emptySet())?.toSet() ?: emptySet()

    fun setRefExcluida(uid: String, sem: Int, tipo: String, excluida: Boolean) {
        if (uid.isBlank()) return
        val key = KEY_REF_EXCL + uid + "_" + sem
        val set = refsExcluidas(uid, sem).toMutableSet()
        if (excluida) set.add(tipo) else set.remove(tipo)
        prefs.edit().putStringSet(key, set).apply()
    }

    /**
     * Borra las preferencias que quedarían colgando de un lote purgado: la última
     * semana vista y las referencias excluidas de cada semana. Cuántas semanas tuvo el
     * lote no se sabe aquí, así que esas se barren por prefijo.
     */
    fun limpiarPreferenciasDeLote(uid: String) {
        val claves = clavesDeLote(uid, prefs.all.keys)
        if (claves.isEmpty()) return
        val editor = prefs.edit()
        claves.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Verifica el PIN contra el hash salado almacenado. Compatibilidad: si todavía hay
     * un PIN en texto plano (o el default "0000"), lo acepta UNA vez y lo migra a hash.
     */
    fun verificarPin(p: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null)
        if (stored != null) return hashPin(p) == stored
        // Legacy: PIN en claro (o default). Si coincide, migrar a hash y borrar el plano.
        val legacy = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        return if (p == legacy) {
            prefs.edit().putString(KEY_PIN_HASH, hashPin(p)).remove(KEY_PIN).apply()
            true
        } else false
    }

    fun setPinHabilitado(activo: Boolean) {
        prefs.edit().putBoolean(KEY_PIN_ON, activo).apply()
        _pinHabilitado.value = activo
    }

    /**
     * Cambia el PIN. Devuelve false si [actual] no coincide o [nuevo] no es de 4 dígitos.
     * El PIN se guarda SOLO como hash salado (SHA-256), nunca en texto plano.
     */
    fun cambiarPin(actual: String, nuevo: String): Boolean {
        if (!verificarPin(actual)) return false
        if (nuevo.length != 4 || !nuevo.all { it.isDigit() }) return false
        prefs.edit().putString(KEY_PIN_HASH, hashPin(nuevo)).remove(KEY_PIN).apply()
        return true
    }

    /** Salt por instalación (se genera una sola vez y se reutiliza). */
    private fun salt(): String {
        prefs.getString(KEY_PIN_SALT, null)?.let { return it }
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val s = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        prefs.edit().putString(KEY_PIN_SALT, s).apply()
        return s
    }

    private fun hashPin(p: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest((salt() + p).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        private const val KEY_PIN_ON = "pin_habilitado"
        private const val KEY_PIN = "pin_valor"          // legacy (texto plano) → se migra a hash
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_MODO_TRAT = "modo_por_tratamiento"
        private const val KEY_ULT_SEM = "ultima_semana_"
        private const val KEY_REF_EXCL = "refs_excluidas_"
        const val DEFAULT_PIN = "0000"

        /**
         * Claves de preferencias que pertenecen al lote [uid] y a nadie más.
         *
         * Pura y sin Android a propósito, para poder probarla: estas claves conviven con
         * el PIN y los ajustes globales en el mismo archivo, y una coincidencia de más al
         * purgar un lote se llevaría por delante el PIN de la app.
         */
        fun clavesDeLote(uid: String, todas: Set<String>): Set<String> {
            if (uid.isBlank()) return emptySet()
            val ultimaSemana = KEY_ULT_SEM + uid
            // El "_" final evita que el uid de un lote alcance al de otro que lo tenga
            // como prefijo.
            val prefijoRefs = KEY_REF_EXCL + uid + "_"
            return todas.filterTo(mutableSetOf()) { it == ultimaSemana || it.startsWith(prefijoRefs) }
        }
    }
}
