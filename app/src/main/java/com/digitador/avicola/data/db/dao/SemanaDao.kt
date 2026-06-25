package com.digitador.avicola.data.db.dao

import androidx.room.*
import com.digitador.avicola.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SemanaDao {

    // ── Semanas ──

    @Query("SELECT * FROM semana WHERE partidaId = :partidaId ORDER BY numero")
    suspend fun getSemanasByPartida(partidaId: Long): List<SemanaEntity>

    @Query("SELECT * FROM semana WHERE partidaId = :partidaId AND numero = :n")
    suspend fun getSemana(partidaId: Long, n: Int): SemanaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSemana(s: SemanaEntity)

    @Query("DELETE FROM semana WHERE partidaId = :partidaId AND numero = :n")
    suspend fun deleteSemana(partidaId: Long, n: Int)

    @Query("DELETE FROM semana WHERE partidaId = :partidaId")
    suspend fun deleteSemanasByPartida(partidaId: Long)

    /** Conteo barato para summaries (evita cargar las semanas enteras). */
    @Query("SELECT COUNT(*) FROM semana WHERE partidaId = :partidaId")
    suspend fun countSemanasByPartida(partidaId: Long): Int

    // ── Datos de parcela ──

    @Query("SELECT * FROM dato_parcela WHERE partidaId = :partidaId AND semanaNumero = :sem AND parcelaId = :pid")
    suspend fun getDato(partidaId: Long, sem: Int, pid: String): DatoParcelaEntity?

    @Query("SELECT * FROM dato_parcela WHERE partidaId = :partidaId")
    suspend fun getAllDatosByPartida(partidaId: Long): List<DatoParcelaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDato(d: DatoParcelaEntity)

    @Query("DELETE FROM dato_parcela WHERE partidaId = :partidaId AND semanaNumero = :sem")
    suspend fun deleteDatosBySemana(partidaId: Long, sem: Int)

    @Query("DELETE FROM dato_parcela WHERE partidaId = :partidaId")
    suspend fun deleteAllDatosByPartida(partidaId: Long)

    // ── Referencias de alimento ──

    @Query("SELECT * FROM ref_alimento WHERE partidaId = :partidaId")
    suspend fun getAllRefsByPartida(partidaId: Long): List<RefAlimentoEntity>

    @Query("SELECT * FROM ref_alimento WHERE partidaId = :partidaId AND semanaNumero = :sem AND parcelaId = :pid")
    suspend fun getRefs(partidaId: Long, sem: Int, pid: String): List<RefAlimentoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRef(r: RefAlimentoEntity)

    @Query("DELETE FROM ref_alimento WHERE partidaId = :partidaId AND semanaNumero = :sem")
    suspend fun deleteRefsBySemana(partidaId: Long, sem: Int)

    @Query("DELETE FROM ref_alimento WHERE partidaId = :partidaId")
    suspend fun deleteAllRefsByPartida(partidaId: Long)
}
