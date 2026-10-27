package com.shortscounter.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.shortscounter.app.data.AppDatabase
import com.shortscounter.app.services.FloatingWidgetService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B0F19)
                ) {
                    val scope = rememberCoroutineScope()
                    val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val db = remember { AppDatabase.getInstance(applicationContext) }

                    var todayCount by remember { mutableStateOf(0) }
                    var appStats by remember { mutableStateOf<List<AppDisplayStat>>(emptyList()) }
                    var isFloatingRunning by remember { mutableStateOf(true) }

                    LaunchedEffect(Unit) {
                        launch {
                            db.videoRecordDao().observeTodayTotalCount(todayDate).collectLatest { count ->
                                todayCount = count
                            }
                        }
                        launch {
                            db.videoRecordDao().observeTodayBreakdown(todayDate).collectLatest { breakdown ->
                                appStats = breakdown.map { b ->
                                    val color = when (b.packageName) {
                                        "com.zhiliaoapp.musically" -> Color(0xFF00F2FE)
                                        "com.instagram.android" -> Color(0xFFE1306C)
                                        "com.google.android.youtube" -> Color(0xFFFF0000)
                                        "com.facebook.katana" -> Color(0xFF1877F2)
                                        else -> Color(0xFFFFFC00)
                                    }
                                    AppDisplayStat(
                                        appName = b.appName,
                                        packageName = b.packageName,
                                        count = b.count,
                                        color = color
                                    )
                                }
                            }
                        }
                    }

                    DashboardScreen(
                        todayCount = todayCount,
                        dailyGoal = 100,
                        appStats = appStats,
                        isFloatingWidgetRunning = isFloatingRunning,
                        onToggleFloatingWidget = { enable ->
                            isFloatingRunning = enable
                            val serviceIntent = Intent(this@MainActivity, FloatingWidgetService::class.java)
                            if (enable) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(serviceIntent)
                                } else {
                                    startService(serviceIntent)
                                }
                            } else {
                                stopService(serviceIntent)
                            }
                        },
                        onResetToday = {
                            scope.launch {
                                db.videoRecordDao().clearTodayData(todayDate)
                            }
                        }
                    )
                }
            }
        }
    }
}