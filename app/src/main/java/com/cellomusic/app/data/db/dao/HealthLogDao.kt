package com.cellomusic.app.data.db.dao

import androidx.room.*
import com.cellomusic.app.data.db.entity.HealthLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthLogDao {

    @Query("SELECT * FROM health_logs ORDER BY timestampMs DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<HealthLogEntity>>

    @Query("SELECT * FROM health_logs WHERE timestampMs BETWEEN :startMs AND :endMs ORDER BY timestampMs DESC")
    fun getInRange(startMs: Long, endMs: Long): Flow<List<HealthLogEntity>>

    @Query("SELECT AVG(strainLevel) FROM health_logs WHERE timestampMs BETWEEN :startMs AND :endMs AND strainLevel > 0")
    suspend fun getAverageStrain(startMs: Long, endMs: Long): Float?

    @Insert
    suspend fun insert(log: HealthLogEntity): Long

    @Delete
    suspend fun delete(log: HealthLogEntity)
}
