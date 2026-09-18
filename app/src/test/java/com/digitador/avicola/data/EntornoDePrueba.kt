package com.digitador.avicola.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.digitador.avicola.data.db.DigitadorDatabase
import com.digitador.avicola.data.repository.ConfigRepository
import com.digitador.avicola.data.repository.DigitadorRepository
import com.digitador.avicola.data.repository.ExportService
import com.digitador.avicola.domain.*

/**
 * Base de datos en memoria + repositorios reales, cableados a mano (sin Hilt).
 * Cada test arranca con una base vacía y unas preferencias limpias.
 */
class EntornoDePrueba {
    val context: Context = ApplicationProvider.getApplicationContext()

    val db: DigitadorDatabase = Room
        .inMemoryDatabaseBuilder(context, DigitadorDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    val config = ConfigRepository(context)
    val repo = DigitadorRepository(db, db.partidaDao(), db.semanaDao(), config)
    val export = ExportService(repo, config)

    init {
        // Las preferencias son de toda la app y sobreviven entre tests de la misma
        // clase; se limpian a mano para que cada uno parta de cero.
        context.getSharedPreferences("config_app", Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun cerrar() = db.close()

    /**
     * Lote de ejemplo: [galeras] galpones × [corrales] tratamientos × [parcelas] jaulas.
     * Los ids siguen la convención de la app (G1, G1-K1, G1-K1-P1).
     */
    fun loteDeEjemplo(
        numero: String = "P-100",
        uid: String = "uid-de-prueba",
        galeras: Int = 2,
        corrales: Int = 2,
        parcelas: Int = 3,
        avesPorJaula: Int = 40
    ) = Partida(
        numero = numero,
        lote = "L-1",
        edad = "1 día",
        fechaInicio = "2026-01-01",
        uid = uid,
        galeras = (1..galeras).map { g ->
            Galera(
                id = "G$g",
                nombre = "Galera $g",
                corrales = (1..corrales).map { k ->
                    Corral(
                        id = "G$g-K$k",
                        galeraId = "G$g",
                        parcelas = (1..parcelas).map { p ->
                            Parcela(
                                id = "G$g-K$k-P$p",
                                corralId = "G$g-K$k",
                                inicio = avesPorJaula,
                                pesoInicio = avesPorJaula * 40.0
                            )
                        }
                    )
                }
            )
        }
    )

    /** Todas las jaulas del lote, en orden. */
    fun jaulas(p: Partida) = p.galeras.flatMap { it.corrales }.flatMap { it.parcelas }
}
