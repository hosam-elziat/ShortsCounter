package com.shortscounter.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * كيان تسجيل كل فيديو قصير يتم مشاهدته
 */
@Entity(tableName = "video_records")
data class VideoRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_name") val appName: String,
    @ColumnInfo(name = "date") val date: String // بصيغة yyyy-MM-dd
)

data class AppCountResult(
    val app_name: String,
    val package_name: String,
    val count: Int
)

data class DailyHistoryResult(
    val date: String,
    val total_count: Int
)

/**
 * واجهة التعامل مع قاعدة البيانات DAO
 */
@Dao
interface VideoRecordDao {

    @Insert
    suspend fun insertRecord(record: VideoRecordEntity)

    // إجمالي الفيديوهات لليوم الحالي
    @Query("SELECT COUNT(*) FROM video_records WHERE date = :todayDate")
    suspend fun getTodayTotalCount(todayDate: String): Int

    // مراقبة حية للإجمالي لليوم الحالي عبر Flow
    @Query("SELECT COUNT(*) FROM video_records WHERE date = :todayDate")
    fun observeTodayTotalCount(todayDate: String): Flow<Int>

    // توزيع الفيديوهات على التطبيقات لليوم الحالي (تيك توك، ريلز، شورتس، إلخ)
    @Query("SELECT app_name, package_name, COUNT(*) as count FROM video_records WHERE date = :todayDate GROUP BY package_name ORDER BY count DESC")
    fun observeTodayBreakdown(todayDate: String): Flow<List<AppCountResult>>

    // إحصائيات الأيام السابقة (آخر 7 أيام)
    @Query("SELECT date, COUNT(*) as total_count FROM video_records GROUP BY date ORDER BY date DESC LIMIT 7")
    fun observeWeeklyHistory(): Flow<List<DailyHistoryResult>>

    // تصفير بيانات اليوم إذا رغب المستخدم
    @Query("DELETE FROM video_records WHERE date = :todayDate")
    suspend fun clearTodayData(todayDate: String)

    // حذف السجلات القديمة جداً (أكثر من 30 يوماً) لتوفير المساحة
    @Query("DELETE FROM video_records WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldRecords(cutoffTimestamp: Long)
}

/**
 * قاعدة بيانات التطبيق Room Database
 */
@Database(entities = [VideoRecordEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun videoRecordDao(): VideoRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shorts_counter_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}