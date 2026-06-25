package com.digitador.avicola.domain

import androidx.compose.runtime.Immutable

// ── Modelos de dominio (independientes de Room) ──────────────
// Marcados @Immutable: nunca se mutan in-place (siempre se recrean), así Compose
// puede saltarse la recomposición de las filas cuyos datos no cambiaron.

@Immutable
data class Partida(
    val id: Long = 0,
    val numero: String = "",
    val lote: String = "",
    val edad: String = "",
    val fechaInicio: String = "",
    val usarGuia: Boolean = true,
    val finalizada: Boolean = false,
    /** UUID único del lote (ver PartidaEntity.uid). */
    val uid: String = "",
    val galeras: List<Galera> = emptyList()
)

@Immutable
data class Galera(
    val id: String,
    val nombre: String,
    val corrales: List<Corral> = emptyList()
)

@Immutable
data class Corral(
    val id: String,
    val galeraId: String,
    val parcelas: List<Parcela> = emptyList()
)

@Immutable
data class Parcela(
    val id: String,
    val corralId: String,
    val inicio: Int = 0,
    val pesoInicio: Double = 0.0
)

@Immutable
data class Semana(
    val numero: Int,
    val fechaInicio: String = "",
    val fechaFin: String = "",
    val refsActivas: List<String> = listOf("BR1"),
    /** true = semana terminada/bloqueada (solo lectura). */
    val cerrada: Boolean = false
)

@Immutable
data class DatoParcela(
    val semanaNumero: Int,
    val parcelaId: String,
    val mort: List<Int?> = List(7) { null },
    val peso: Double? = null,
    val pesos: List<Double> = emptyList(), // NUEVO: Múltiples muestreos
    val consAjust: Double? = null,
    val refs: Map<String, RefAlimento> = emptyMap()
)

@Immutable
data class RefAlimento(
    val tipo: String,
    val ingreso: Double? = null,
    val saldoFin: Double? = null
)

// ── Resultados de cálculo ─────────────────────────────────────

data class MetricasParcela(
    val inicio: Int,
    val mort: Int,
    val saldo: Int,
    val pesoTotal: Double,
    val promGave: Double,
    val consumoSem: Double,
    val consGave: Double,
    val cgr: Double
)

data class MetricasCorral(
    val saldo: Int,
    val promPeso: Double,
    val consumoGave: Double,
    val consumoAcum: Double,
    val fcrSem: Double?,
    val fcrAcum: Double?,
    val mortAcumPct: Double,
    val gdpSem: Double,
    val gdpLineal: Double,
    val cvPeso: Double?,
    val cgr: Double?,
    val fep: Double? = null, // Factor de Eficiencia Productiva
    val fcrAdj: Double? = null,
    val ratio: Double? = null
)

/**
 * Coeficiente de variación del peso de un grupo de jaulas (tratamiento, galera o
 * todo el lote). Se calcula como en la planilla: desviación estándar MUESTRAL (n−1)
 * de los pesos promedio de las jaulas, dividida por el promedio.
 */
data class CvPeso(
    val n: Int,
    val media: Double,   // promedio de los pesos g/ave de las jaulas (sin ponderar)
    val desv: Double     // desviación estándar muestral (n−1)
) {
    val dosSigma: Double get() = desv * 2.0
    /** Coeficiente de variación como fracción (×100 para porcentaje). */
    val cv: Double get() = if (media > 0) desv / media else 0.0
}

data class ProgresoSemana(
    val semanaNumero: Int,
    val filled: Int,
    val total: Int,
    val mfilled: Int,
    val mtotal: Int
) {
    val pct: Int get() = if (total > 0) (filled * 100 / total) else 0
    val mpct: Int get() = if (mtotal > 0) (mfilled * 100 / mtotal) else 0
    val status: EstadoSemana get() = when {
        pct == 100 -> EstadoSemana.COMPLETA
        pct > 0 -> EstadoSemana.PARCIAL
        else -> EstadoSemana.VACIA
    }
}

enum class EstadoSemana { COMPLETA, PARCIAL, VACIA }

/** Resultado de validar la completitud de una semana antes de cerrarla. */
data class ValidacionSemana(
    val totalParcelas: Int,
    val faltanPeso: Int,
    val faltanAlimento: Int
) {
    val completa: Boolean get() = totalParcelas > 0 && faltanPeso == 0 && faltanAlimento == 0
}

data class PartidaSummary(
    val id: Long,
    val numero: String,
    val lote: String,
    val avesIniciales: Int,
    val avesActuales: Int,
    val ultimaSemana: Int,
    val progreso: Int,
    val finalizada: Boolean,
    /** Una partida es "pendiente" cuando solo tiene la distribución cargada
     *  pero falta digitar identificación (Nº/fecha) o recepción. */
    val pendiente: Boolean = false,
    /** Total de parcelas en la distribución (siempre conocido). */
    val parcelasTotal: Int = 0,
    /** Peso total de recepción (g). 0 hasta que se digita el Paso 2. */
    val pesoInicialTotal: Double = 0.0,
    /** Nº de pasos completados del flujo de creación (0..3 para pendientes,
     *  4 cuando ya dejó de ser pendiente). */
    val pasosCompletados: Int = 0
)

/** Ítem de la papelera: lote eliminado con su cuenta regresiva de purga. */
data class PapeleraItem(
    val id: Long,
    val numero: String,
    val lote: String,
    val eliminadaEn: Long,
    val diasRestantes: Int
)

val TIPOS_ALIMENTO = listOf("BR1", "BR2", "BR3", "BR4")
val DIAS_SEMANA = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")

/**
 * Opciones del export a Excel. Las "métricas adicionales" (FEP, FCR ajustado a peso
 * estándar y CV%/uniformidad por corral) NO existen en la planilla fuente del ensayo;
 * por eso son opcionales. Por defecto se EXCLUYEN para que el Excel coincida 1:1 con la
 * planilla; el usuario puede activarlas desde la pantalla de análisis.
 */
data class OpcionesExport(
    val incluirFep: Boolean = false,
    val incluirFcrAjustado: Boolean = false,
    val incluirCv: Boolean = false
) {
    val incluyeExtras: Boolean get() = incluirFep || incluirFcrAjustado || incluirCv

    companion object {
        /** Igual que la planilla: sin métricas adicionales. */
        val SOLO_PLANILLA = OpcionesExport(false, false, false)
        /** Todas las métricas adicionales activadas. */
        val CON_EXTRAS = OpcionesExport(true, true, true)
    }
}
