package com.cellomusic.app.data.repository

import com.cellomusic.app.data.db.dao.PracticeSessionDao
import com.cellomusic.app.data.db.entity.PracticeSessionEntity
import kotlinx.coroutines.flow.Flow

class JournalRepository(private val sessionDao: PracticeSessionDao) {

    fun getRecentSessions(limit: Int = 14): Flow<List<PracticeSessionEntity>> =
        sessionDao.getRecent(limit)

    fun getAllSessions(): Flow<List<PracticeSessionEntity>> = sessionDao.getAll()

    fun getSessionsInRange(startMs: Long, endMs: Long): Flow<List<PracticeSessionEntity>> =
        sessionDao.getSessionsInRange(startMs, endMs)

    suspend fun getDistinctPracticeDays(): List<Long> = sessionDao.getDistinctPracticeDays()

    suspend fun getDailyTotals(startMs: Long, endMs: Long) = sessionDao.getDailyTotals(startMs, endMs)

    suspend fun saveSession(
        pieceName: String,
        notes: String,
        durationMin: Int,
        selfEval: Int,
        challenge: String = "",
        nextTimeNote: String = "",
        recordingPath: String? = null,
        scoreId: String? = null,
        category: String = "",
        bpm: Int = 0
    ): PracticeSessionEntity {
        val points = durationMin * 10
        val entity = PracticeSessionEntity(
            timestampMs = System.currentTimeMillis(),
            pieceName = pieceName,
            notes = notes,
            durationMin = durationMin,
            selfEval = selfEval.coerceIn(1, 5),
            challenge = challenge,
            nextTimeNote = nextTimeNote,
            recordingPath = recordingPath,
            scoreId = scoreId,
            category = category,
            totalPoints = points,
            bpm = bpm
        )
        val id = sessionDao.insert(entity)
        return entity.copy(id = id)
    }

    suspend fun deleteSession(session: PracticeSessionEntity) = sessionDao.delete(session)

    /**
     * Strip the recordingPath from a session — used when playback discovers
     * the .m4a file is gone (deleted externally, cache cleared, etc.) so the
     * row no longer advertises a recording that isn't there.
     */
    suspend fun clearRecordingPath(session: PracticeSessionEntity) {
        if (session.recordingPath == null) return
        sessionDao.update(session.copy(recordingPath = null))
    }

    suspend fun getTotalMinutesInRange(startMs: Long, endMs: Long): Int =
        sessionDao.getTotalMinutesInRange(startMs, endMs) ?: 0

    suspend fun countSessionsInRangeByCategory(startMs: Long, endMs: Long, category: String): Int =
        sessionDao.countSessionsInRangeByCategory(startMs, endMs, category)

    suspend fun getTotalMinutesInRangeByCategory(startMs: Long, endMs: Long, category: String): Int =
        sessionDao.getTotalMinutesInRangeByCategory(startMs, endMs, category) ?: 0

    suspend fun getCategoryTotals(startMs: Long, endMs: Long) =
        sessionDao.getCategoryTotals(startMs, endMs)

    suspend fun getAvgSelfEvalInRange(startMs: Long, endMs: Long): Float =
        sessionDao.getAvgSelfEvalInRange(startMs, endMs) ?: 0f

    suspend fun countSessionsInRange(startMs: Long, endMs: Long): Int =
        sessionDao.countSessionsInRange(startMs, endMs)
}
