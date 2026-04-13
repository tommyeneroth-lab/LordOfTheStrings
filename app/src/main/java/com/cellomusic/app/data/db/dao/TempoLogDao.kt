package com.cellomusic.app.data.db.dao

import androidx.room.*
import com.cellomusic.app.data.db.entity.TempoLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TempoLogDao {

    @Query("SELECT * FROM tempo_logs WHERE pieceName = :pieceName ORDER BY timestampMs ASC")
    fun getTempoHistoryByPiece(pieceName: String): Flow<List<TempoLogEntity>>

    @Query("SELECT * FROM tempo_logs WHERE scoreId = :scoreId ORDER BY timestampMs ASC")
    fun getTempoHistoryByScore(scoreId: String): Flow<List<TempoLogEntity>>

    @Query("SELECT * FROM tempo_logs ORDER BY timestampMs DESC LIMIT :limit")
    fun getRecentLogs(limit: Int): Flow<List<TempoLogEntity>>

    @Query("SELECT DISTINCT pieceName FROM tempo_logs ORDER BY pieceName ASC")
    suspend fun getDistinctPieceNames(): List<String>

    @Insert
    suspend fun insert(log: TempoLogEntity): Long

    @Delete
    suspend fun delete(log: TempoLogEntity)
}
