package com.digitador.avicola.data.db.entity

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

// ── Converters ──────────────────────────────────────────────

class Converters {
    // Gson y los Type se cachean una sola vez: crear `object : TypeToken<…>(){}`
    // en cada conversión usa reflexión y se repetía por cada fila leída.
    private companion object {
        val gson = Gson()
        val stringListType: Type = object : TypeToken<List<String>>() {}.type
        val nullableIntListType: Type = object : TypeToken<List<Int?>>() {}.type
        val doubleListType: Type = object : TypeToken<List<Double>>() {}.type
        val intListType: Type = object : TypeToken<List<Int>>() {}.type
    }

    @TypeConverter fun fromStringList(v: List<String>): String = gson.toJson(v)
    @TypeConverter fun toStringList(v: String): List<String> = gson.fromJson(v, stringListType) ?: emptyList()
    @TypeConverter fun fromNullableIntList(v: List<Int?>): String = gson.toJson(v)
    @TypeConverter fun toNullableIntList(v: String): List<Int?> = gson.fromJson(v, nullableIntListType) ?: List(7) { null }

    @TypeConverter fun fromDoubleList(v: List<Double>): String = gson.toJson(v)
    @TypeConverter fun toDoubleList(v: String): List<Double> = gson.fromJson(v, doubleListType) ?: emptyList()

    @TypeConverter fun fromIntList(v: List<Int>): String = gson.toJson(v)
    @TypeConverter fun toIntList(v: String): List<Int> = gson.fromJson(v, intListType) ?: emptyList()
}

// ── Partida ──────────────────────────────────────────────────

@Entity(tableName = "partida")
@TypeConverters(Converters::class)
data class PartidaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val numero: String = "",
    val lote: String = "",
    val edad: String = "",
    val fechaInicio: String = "",
    /** Identificador único e inmutable del lote (UUID). Evita recargar el mismo
     *  lote dos veces y permite generar copias. Se genera al crear la partida. */
    val uid: String = "",
    // OBSOLETO: la app no usa "línea genética". Columna inerte conservada solo para
    // no forzar una migración destructiva de la BD. No leer ni escribir.
    val lineaGenetica: String = "",
    val usarGuia: Boolean = true,
    val finalizada: Boolean = false,
    /** Soft delete: 0 = activa; >0 = timestamp (ms) en que se movió a la papelera.
     *  Se purga definitivamente tras 15 días. */
    val eliminadaEn: Long = 0L
)

// ── Galera ───────────────────────────────────────────────────

@Entity(
    tableName = "galera",
    primaryKeys = ["id", "partidaId"],
    foreignKeys = [ForeignKey(entity = PartidaEntity::class, parentColumns = ["id"], childColumns = ["partidaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("partidaId")]
)
data class GaleraEntity(
    val id: String, 
    val partidaId: Long,
    val nombre: String,
    val orden: Int = 0
)

// ── Corral ───────────────────────────────────────────────────

@Entity(
    tableName = "corral",
    primaryKeys = ["id", "partidaId"],
    foreignKeys = [
        ForeignKey(entity = GaleraEntity::class, parentColumns = ["id", "partidaId"], childColumns = ["galeraId", "partidaId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("galeraId", "partidaId"), Index("partidaId")]
)
data class CorralEntity(
    val id: String,
    val partidaId: Long,
    val galeraId: String,
    val orden: Int = 0
)

// ── Parcela ──────────────────────────────────────────────────

@Entity(
    tableName = "parcela",
    primaryKeys = ["id", "partidaId"],
    foreignKeys = [
        ForeignKey(entity = CorralEntity::class, parentColumns = ["id", "partidaId"], childColumns = ["corralId", "partidaId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("corralId", "partidaId"), Index("partidaId")]
)
data class ParcelaEntity(
    val id: String,
    val partidaId: Long,
    val corralId: String,
    val inicio: Int = 0,
    val pesoInicio: Double = 0.0,
    val orden: Int = 0,
    /** Jaula retirada del análisis (ver `Parcela.suspendida`). */
    val suspendida: Boolean = false,
    val suspendidaEn: Long = 0L,
    val suspendidaMotivo: String = ""
)

// ── Semana ───────────────────────────────────────────────────

@Entity(
    tableName = "semana",
    primaryKeys = ["partidaId", "numero"],
    foreignKeys = [ForeignKey(entity = PartidaEntity::class, parentColumns = ["id"], childColumns = ["partidaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("partidaId")]
)
@TypeConverters(Converters::class)
data class SemanaEntity(
    val partidaId: Long,
    val numero: Int,
    val fechaInicio: String = "",
    val fechaFin: String = "",
    val refsActivas: List<String> = listOf("BR1"),
    /** true = semana terminada/bloqueada: su digitación queda en solo lectura. */
    val cerrada: Boolean = false
)

// ── Dato por parcela/semana ───────────────────────────────────

@Entity(
    tableName = "dato_parcela",
    primaryKeys = ["partidaId", "semanaNumero", "parcelaId"],
    foreignKeys = [
        ForeignKey(entity = ParcelaEntity::class, parentColumns = ["id", "partidaId"], childColumns = ["parcelaId", "partidaId"], onDelete = ForeignKey.CASCADE)
    ],
    // El índice de parcelaId es COMPUESTO con partidaId para que cubra la clave foránea
    // de arriba. Con un índice suelto por parcelaId el planificador ni siquiera lo usaba:
    // caía en el autoíndice de la clave primaria y recorría TODAS las filas del lote
    // filtrando por parcelaId, en cada fila de `parcela` que se borre (borrado en cascada
    // al purgar la papelera o al reimportar una distribución distinta).
    // El compuesto sirve igual las búsquedas por parcelaId, porque es su prefijo.
    indices = [Index("partidaId"), Index("semanaNumero"), Index("parcelaId", "partidaId")]
)
@TypeConverters(Converters::class)
data class DatoParcelaEntity(
    val partidaId: Long,
    val semanaNumero: Int,
    val parcelaId: String,
    val mort: List<Int?> = List(7) { null },
    val peso: Double? = null,
    val pesos: List<Double> = emptyList(),
    val consAjust: Double? = null
)

// ── Referencia de alimento ────────────────────────────────────

@Entity(
    tableName = "ref_alimento",
    primaryKeys = ["partidaId", "semanaNumero", "parcelaId", "tipo"],
    foreignKeys = [
        ForeignKey(entity = ParcelaEntity::class, parentColumns = ["id", "partidaId"], childColumns = ["parcelaId", "partidaId"], onDelete = ForeignKey.CASCADE)
    ],
    // Compuesto para cubrir la clave foránea, igual que en dato_parcela.
    indices = [Index("partidaId"), Index("semanaNumero"), Index("parcelaId", "partidaId")]
)
data class RefAlimentoEntity(
    val partidaId: Long,
    val semanaNumero: Int,
    val parcelaId: String,
    val tipo: String,
    val ingreso: Double? = null,
    val saldoFin: Double? = null
)

// ── Borradores de Lotes ──────────────────────────────────────

@Entity(tableName = "borrador_lote")
data class BorradorLoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val nombre: String,
    val fechaGuardado: Long,
    val jsonData: String
)
