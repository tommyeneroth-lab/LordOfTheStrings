package com.cellomusic.app.data.repository

import com.cellomusic.app.data.db.dao.HealthLogDao
import com.cellomusic.app.data.db.entity.HealthLogEntity
import kotlinx.coroutines.flow.Flow

class HealthRepository(private val dao: HealthLogDao) {

    fun getRecent(limit: Int = 10): Flow<List<HealthLogEntity>> = dao.getRecent(limit)

    fun getInRange(startMs: Long, endMs: Long): Flow<List<HealthLogEntity>> =
        dao.getInRange(startMs, endMs)

    suspend fun getAverageStrain(startMs: Long, endMs: Long): Float =
        dao.getAverageStrain(startMs, endMs) ?: 0f

    suspend fun logHealth(
        sessionId: Long? = null,
        strainLevel: Int,
        strainArea: String = "",
        breakTakenMin: Int = 0,
        notes: String = ""
    ): Long = dao.insert(
        HealthLogEntity(
            sessionId = sessionId,
            strainLevel = strainLevel.coerceIn(0, 5),
            strainArea = strainArea,
            breakTakenMin = breakTakenMin,
            notes = notes
        )
    )
}
