package com.digitador.avicola.domain

import java.time.LocalDate

/** Utilidades de fecha centralizadas (antes duplicadas en varios ViewModels). */
object DateUtils {

    /**
     * Suma [n] días a una fecha ISO (yyyy-MM-dd). Devuelve "" si [dateStr] no es
     * una fecha válida, para no romper el flujo aguas arriba.
     */
    fun addDays(dateStr: String, n: Int): String = try {
        LocalDate.parse(dateStr).plusDays(n.toLong()).toString()
    } catch (e: Exception) {
        ""
    }
}
