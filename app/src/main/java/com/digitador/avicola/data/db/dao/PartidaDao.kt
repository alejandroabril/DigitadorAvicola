package com.digitador.avicola.data.db.dao

import androidx.room.*
import com.digitador.avicola.data.db.entity.*

@Dao
interface PartidaDao {

    // ── Partida ──

    @Query("SELECT * FROM partida ORDER BY id DESC")
    suspend fun getAllPartidasSync(): List<PartidaEntity>

    /** Solo lotes activos (no en papelera) — para el historial. */
    @Query("SELECT * FROM partida WHERE eliminadaEn = 0 ORDER BY id DESC")
    suspend fun getActivasSync(): List<PartidaEntity>

    /** Lotes en la papelera, más reciente primero. */
    @Query("SELECT * FROM partida WHERE eliminadaEn > 0 ORDER BY eliminadaEn DESC")
    suspend fun getPapeleraSync(): List<PartidaEntity>

    /** Mueve a la papelera (soft delete). */
    @Query("UPDATE partida SET eliminadaEn = :ts WHERE id = :id")
    suspend fun softDelete(id: Long, ts: Long)

    /** Restaura desde la papelera. */
    @Query("UPDATE partida SET eliminadaEn = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    /** IDs de lotes cuya eliminación ya venció (para purga definitiva). */
    @Query("SELECT id FROM partida WHERE eliminadaEn > 0 AND eliminadaEn < :limite")
    suspend fun getExpiradasIds(limite: Long): List<Long>

    @Query("SELECT * FROM partida WHERE id = :id")
    suspend fun getPartidaById(id: Long): PartidaEntity?

    @Query("SELECT * FROM partida ORDER BY id DESC LIMIT 1")
    suspend fun getLatestPartida(): PartidaEntity?

    /** Unicidad de lote: cuántas partidas tienen este UID (recarga de backup). */
    @Query("SELECT COUNT(*) FROM partida WHERE uid = :uid")
    suspend fun countByUid(uid: String): Int

    /** Unicidad del número de partida entre lotes ACTIVOS (excluye la propia al editar). */
    @Query("SELECT COUNT(*) FROM partida WHERE numero = :numero AND id != :excludeId AND eliminadaEn = 0")
    suspend fun countByNumeroExcept(numero: String, excludeId: Long): Int

    /** Números de lotes activos (para generar el sufijo de copia). */
    @Query("SELECT numero FROM partida WHERE eliminadaEn = 0")
    suspend fun getAllNumeros(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPartida(p: PartidaEntity): Long

    @Update
    suspend fun updatePartida(p: PartidaEntity)

    @Transaction
    suspend fun upsertPartida(p: PartidaEntity): Long {
        val id = insertPartida(p)
        if (id == -1L) {
            updatePartida(p)
            return p.id
        }
        return id
    }

    @Query("UPDATE partida SET fechaInicio = :fecha WHERE id = :id")
    suspend fun updateFechaInicio(id: Long, fecha: String)

    @Query("DELETE FROM partida WHERE id = :id")
    suspend fun deletePartidaById(id: Long)

    // ── Galeras ──

    @Query("SELECT * FROM galera WHERE partidaId = :partidaId ORDER BY orden")
    suspend fun getGalerasByPartida(partidaId: Long): List<GaleraEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGalera(g: GaleraEntity): Long

    @Update
    suspend fun updateGalera(g: GaleraEntity)

    @Transaction
    suspend fun upsertGalera(g: GaleraEntity) {
        if (insertGalera(g) == -1L) updateGalera(g)
    }

    @Query("DELETE FROM galera WHERE partidaId = :partidaId")
    suspend fun deleteGalerasByPartida(partidaId: Long)

    @Query("DELETE FROM galera WHERE partidaId = :partidaId AND id NOT IN (:ids)")
    suspend fun deleteGalerasNotIn(partidaId: Long, ids: List<String>)

    // ── Corrales ──

    @Query("SELECT * FROM corral WHERE partidaId = :partidaId ORDER BY orden")
    suspend fun getCorralesByPartida(partidaId: Long): List<CorralEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCorral(c: CorralEntity): Long

    @Update
    suspend fun updateCorral(c: CorralEntity)

    @Transaction
    suspend fun upsertCorral(c: CorralEntity) {
        if (insertCorral(c) == -1L) updateCorral(c)
    }

    @Query("DELETE FROM corral WHERE partidaId = :partidaId")
    suspend fun deleteCorralesByPartida(partidaId: Long)

    @Query("DELETE FROM corral WHERE partidaId = :partidaId AND id NOT IN (:ids)")
    suspend fun deleteCorralesNotIn(partidaId: Long, ids: List<String>)

    // ── Parcelas ──

    @Query("SELECT * FROM parcela WHERE partidaId = :partidaId ORDER BY orden")
    suspend fun getParcelasByPartida(partidaId: Long): List<ParcelaEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertParcela(p: ParcelaEntity): Long

    @Update
    suspend fun updateParcela(p: ParcelaEntity)

    @Transaction
    suspend fun upsertParcela(p: ParcelaEntity) {
        if (insertParcela(p) == -1L) updateParcela(p)
    }

    @Query("SELECT * FROM parcela WHERE id = :id AND partidaId = :partidaId")
    suspend fun getParcela(id: String, partidaId: Long): ParcelaEntity?

    @Query("DELETE FROM parcela WHERE partidaId = :partidaId")
    suspend fun deleteParcelasByPartida(partidaId: Long)

    /** Actualización parcial de la recepción de una parcela (auto-save por paso). */
    @Query("UPDATE parcela SET inicio = :inicio, pesoInicio = :pesoInicio WHERE id = :id AND partidaId = :partidaId")
    suspend fun updateParcelaInicio(partidaId: Long, id: String, inicio: Int, pesoInicio: Double)

    // ── Agregados para summaries rápidos (evitan cargar el estado completo) ──

    @Query("SELECT IFNULL(SUM(inicio), 0) FROM parcela WHERE partidaId = :id")
    suspend fun sumAvesByPartida(id: Long): Int

    @Query("SELECT IFNULL(SUM(pesoInicio), 0.0) FROM parcela WHERE partidaId = :id")
    suspend fun sumPesoByPartida(id: Long): Double

    @Query("SELECT COUNT(*) FROM parcela WHERE partidaId = :id")
    suspend fun countParcelasByPartida(id: Long): Int

    @Query("DELETE FROM parcela WHERE partidaId = :partidaId AND id NOT IN (:ids)")
    suspend fun deleteParcelasNotIn(partidaId: Long, ids: List<String>)

    // ── Borradores ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBorrador(b: BorradorLoteEntity)

    @Query("SELECT * FROM borrador_lote ORDER BY fechaGuardado DESC")
    suspend fun getBorradores(): List<BorradorLoteEntity>

    @Query("DELETE FROM borrador_lote WHERE id = :id")
    suspend fun deleteBorrador(id: Long)
}
