package com.digitador.avicola.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.digitador.avicola.data.db.dao.PartidaDao
import com.digitador.avicola.data.db.dao.SemanaDao
import com.digitador.avicola.data.db.entity.*

@Database(
    entities = [
        PartidaEntity::class,
        GaleraEntity::class,
        CorralEntity::class,
        ParcelaEntity::class,
        SemanaEntity::class,
        DatoParcelaEntity::class,
        RefAlimentoEntity::class,
        BorradorLoteEntity::class
    ],
    version = 12,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class DigitadorDatabase : RoomDatabase() {

    abstract fun partidaDao(): PartidaDao
    abstract fun semanaDao(): SemanaDao

    companion object {
        @Volatile private var INSTANCE: DigitadorDatabase? = null

        // ── Migraciones reales (preservan los datos del usuario) ──────────────
        // El esquema se exporta a /schemas desde la v7; cada salto solo AÑADE columnas.
        // Las columnas nuevas no declaran defaultValue en la entidad, así que Room no
        // valida el DEFAULT del ALTER (necesario para columnas NOT NULL en SQLite).
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE partida ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                // Asignar un uid único a cada lote existente (hex aleatorio de 16 bytes).
                db.execSQL("UPDATE partida SET uid = lower(hex(randomblob(16))) WHERE uid = ''")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE partida ADD COLUMN eliminadaEn INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE semana ADD COLUMN cerrada INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Índices que cubren la clave foránea (parcelaId, partidaId) de `dato_parcela`
         * y `ref_alimento`, para el borrado en cascada de `parcela`.
         *
         * No cambia ningún dato: solo crea los índices compuestos y quita los sueltos por
         * parcelaId, que el compuesto ya cubre como prefijo.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_dato_parcela_parcelaId")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dato_parcela_parcelaId_partidaId ON dato_parcela (parcelaId, partidaId)")
                db.execSQL("DROP INDEX IF EXISTS index_ref_alimento_parcelaId")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ref_alimento_parcelaId_partidaId ON ref_alimento (parcelaId, partidaId)")
            }
        }

        /**
         * Suspensión de jaulas del análisis. Solo añade columnas; ningún lote existente
         * cambia de comportamiento, porque todas nacen sin suspender.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parcela ADD COLUMN suspendida INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE parcela ADD COLUMN suspendidaEn INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE parcela ADD COLUMN suspendidaMotivo TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): DigitadorDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    DigitadorDatabase::class.java,
                    "digitador_avicola.db"
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    // Solo recrea la BD (con pérdida) si se viene de versiones PREVIAS a la
                    // exportación de esquema (1..6), improbables en campo. De la v7 en adelante
                    // se migran datos; un salto futuro SIN migración fallará en vez de borrar
                    // en silencio → obliga a escribir la migración.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
