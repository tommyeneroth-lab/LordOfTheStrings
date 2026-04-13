package com.cellomusic.app.data.repository

import com.cellomusic.app.data.db.dao.TempoLogDao
import com.cellomusic.app.data.db.entity.TempoLogEntity
import kotlinx.coroutines.flow.Flow

class TempoRepository(private val dao: TempoLogDao) {

    fun getTempoHistoryByPiece(pieceName: String): Flow<List<TempoLogEntity>> =
        dao.getTempoHistoryByPiece(pieceName)

    fun getTempoHistoryByScore(scoreId: String): Flow<List<TempoLogEntity>> =
        dao.getTempoHistoryByScore(scoreId)

    fun getRecentLogs(limit: Int = 50): Flow<List<TempoLogEntity>> = dao.getRecentLogs(limit)

    suspend fun getDistinctPieceNames(): List<String> = dao.getDistinctPieceNames()

    suspend fun logTempo(
        pieceName: String,
        bpm: Int,
        scoreId: String? = null,
        sessionId: Long? = null
    ): Long = dao.insert(
        TempoLogEntity(
            pieceName = pieceName,
            bpm = bpm,
            scoreId = scoreId,
            sessionId = sessionId
        )
    )
}
