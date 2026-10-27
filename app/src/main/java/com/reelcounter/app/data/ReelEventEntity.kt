package com.reelcounter.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reel_events")
data class ReelEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val packageName: String,
    val appName: String,
    val date: String // بصيغة yyyy-MM-dd
)