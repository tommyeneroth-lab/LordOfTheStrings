package com.cellomusic.app.data.db.dao

import androidx.room.*
import com.cellomusic.app.data.db.entity.ScoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScoreDao {

    @Query("SELECT * FROM scores ORDER BY lastOpened DESC, dateAdded DESC")
    fun getAllScores(): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores WHERE title LIKE '%' || :query || '%' OR composer LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchScores(query: String): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavorites(): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores WHERE id = :id")
    suspend fun getScoreById(id: Long): ScoreEntity?

    @Query("SELECT * FROM scores WHERE scoreId = :scoreId")
    suspend fun getScoreByScoreId(scoreId: String): ScoreEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScore(score: ScoreEntity): Long

    @Update
    suspend fun updateScore(score: ScoreEntity)

    @Query("UPDATE scores SET lastOpened = :timestamp, lastMeasure = :measure WHERE id = :id")
    suspend fun updateLastOpened(id: Long, timestamp: Long, measure: Int)

    @Query("UPDATE scores SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Delete
    suspend fun deleteScore(score: ScoreEntity)

    @Query("DELETE FROM scores WHERE id = :id")
    suspend fun deleteScoreById(id: Long)

    @Query("SELECT COUNT(*) FROM scores")
    suspend fun getCount(): Int
}
