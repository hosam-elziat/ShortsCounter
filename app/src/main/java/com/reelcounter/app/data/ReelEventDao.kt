package com.reelcounter.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class AppBreakdown(
    val packageName: String,
    val appName: String,
    val count: Int
)

@Dao
interface ReelEventDao {

    @Insert
    suspend fun insertEvent(event: ReelEventEntity): Long

    @Query("SELECT COUNT(*) FROM reel_events WHERE date = :date")
    suspend fun getTodayTotalCount(date: String): Int

    @Query("SELECT COUNT(*) FROM reel_events WHERE date = :date")
    fun observeTodayTotalCount(date: String): Flow<Int>

    @Query("SELECT packageName, appName, COUNT(*) as count FROM reel_events WHERE date = :date GROUP BY packageName ORDER BY count DESC")
    fun observeTodayBreakdown(date: String): Flow<List<AppBreakdown>>

    @Query("DELETE FROM reel_events WHERE date = :date")
    suspend fun clearTodayEvents(date: String)
}