package com.cellomusic.app.data.db.dao

import androidx.room.*
import com.cellomusic.app.data.db.entity.MeasurePracticeEntity

@Dao
interface MeasurePracticeDao {

    /** Get the total time spent on each measure for a given score. */
    @Query("""
        SELECT measureNumber, SUM(durationSec) AS totalSec
        FROM measure_practice
        WHERE scoreId = :scoreId
        GROUP BY measureNumber
        ORDER BY measureNumber ASC
    """)
    suspend fun getMeasureFrequency(scoreId: String): List<MeasureFrequency>

    @Insert
    suspend fun insert(entry: MeasurePracticeEntity)

    @Insert
    suspend fun insertAll(entries: List<MeasurePracticeEntity>)

    @Query("DELETE FROM measure_practice WHERE scoreId = :scoreId")
    suspend fun deleteByScore(scoreId: String)

    data class MeasureFrequency(val measureNumber: Int, val totalSec: Int)
}
