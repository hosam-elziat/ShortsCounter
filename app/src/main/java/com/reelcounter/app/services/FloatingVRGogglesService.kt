package com.reelcounter.app.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.reelcounter.app.R
import com.reelcounter.app.ReelCounterApp
import com.reelcounter.app.data.AppDatabase
import com.reelcounter.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FloatingVRGogglesService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var miniDashboardView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private lateinit var tvLiveBadge: TextView
    private lateinit var ivVrGlasses: ImageView

    private var currentCount = 0
    private var isCountingPaused = false
    private var dailyGoal = 100

    private val NOTIFICATION_ID = 2002

    companion object {
        const val ACTION_UPDATE_COUNT = "com.reelcounter.app.ACTION_UPDATE_COUNT"
        const val ACTION_TOGGLE_PAUSE = "com.reelcounter.app.ACTION_TOGGLE_PAUSE"
        const val ACTION_HIDE_WIDGET = "com.reelcounter.app.ACTION_HIDE_WIDGET"
        const val ACTION_SHOW_WIDGET = "com.reelcounter.app.ACTION_SHOW_WIDGET"
        private const val PREFS_NAME = "reel_counter_vr_prefs"
        private const val KEY_POS_X = "vr_pos_x"
        private const val KEY_POS_Y = "vr_pos_y"
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_COUNT -> {
                    val count = intent.getIntExtra(ReelAccessibilityService.EXTRA_TOTAL_COUNT, -1)
                    if (count >= 0) {
                        updateBadge(count)
                    } else {
                        refreshCountFromDatabase()
                    }
                }
                ACTION_TOGGLE_PAUSE -> {
                    isCountingPaused = !isCountingPaused
                }
                ACTION_HIDE_WIDGET -> {
                    floatingView?.visibility = View.GONE
                }
                ACTION_SHOW_WIDGET -> {
                    floatingView?.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        startForeground(NOTIFICATION_ID, buildOngoingNotification("0 فيديو اليوم"))

        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_COUNT)
            addAction(ACTION_TOGGLE_PAUSE)
            addAction(ACTION_HIDE_WIDGET)
            addAction(ACTION_SHOW_WIDGET)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }

        createVRFloatingWidget()
        refreshCountFromDatabase()
    }

    @SuppressLint("InflateParams")
    private fun createVRFloatingWidget() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_vr_goggles, null)

        tvLiveBadge = floatingView!!.findViewById(R.id.tv_vr_live_counter)
        ivVrGlasses = floatingView!!.findViewById(R.id.iv_vr_headset_icon)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // استعادة المكان السابق للأيقونة العائمة
        val savedX = prefs.getInt(KEY_POS_X, 30)
        val savedY = prefs.getInt(KEY_POS_Y, 220)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        windowManager.addView(floatingView, layoutParams)
        attachTouchAndDragListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchAndDragListener() {
        var startX = 0
        var startY = 0
        var touchRawX = 0f
        var touchRawY = 0f
        var isSingleTap = false

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchRawX = event.rawX
                    touchRawY = event.rawY
                    isSingleTap = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - touchRawX).toInt()
                    val deltaY = (event.rawY - touchRawY).toInt()

                    if (Math.abs(deltaX) > 12 || Math.abs(deltaY) > 12) {
                        isSingleTap = false
                    }

                    layoutParams.x = startX + deltaX
                    layoutParams.y = startY + deltaY
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isSingleTap) {
                        // النقر على نظارة الـ VR يفتح التطبيق أو لوحة التحكم المصغرة
                        openFullApplication()
                    } else {
                        // التصاق جانبي ذكي بحافة الشاشة وحفظ الموقع
                        dockToNearestScreenEdge()
                        saveWidgetCoordinates()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dockToNearestScreenEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val viewWidth = floatingView?.width ?: 120
        val mid = screenWidth / 2

        layoutParams.x = if (layoutParams.x + viewWidth / 2 < mid) 20 else screenWidth - viewWidth - 20
        windowManager.updateViewLayout(floatingView, layoutParams)
    }

    private fun saveWidgetCoordinates() {
        prefs.edit()
            .putInt(KEY_POS_X, layoutParams.x)
            .putInt(KEY_POS_Y, layoutParams.y)
            .apply()
    }

    private fun openFullApplication() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    fun updateBadge(count: Int) {
        currentCount = count
        tvLiveBadge.text = count.toString()

        if (count >= dailyGoal) {
            tvLiveBadge.setTextColor(Color.parseColor("#FF3B30"))
            vibrateAlert()
        } else {
            tvLiveBadge.setTextColor(Color.parseColor("#00F2FE"))
        }

        // تحديث إشعار الخدمة
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildOngoingNotification("$count فيديو شاهدتها اليوم"))
    }

    private fun vibrateAlert() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(140)
        }
    }

    private fun refreshCountFromDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(applicationContext)
            val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val count = db.reelEventDao().getTodayTotalCount(todayDate)
            withContext(Dispatchers.Main) {
                updateBadge(count)
            }
        }
    }

    private fun buildOngoingNotification(body: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ReelCounterApp.CHANNEL_ID)
            .setContentTitle("عداد الريلز والفيديوهات (ReelCounter)")
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_vr_glasses)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {}

        if (floatingView != null) {
            windowManager.removeView(floatingView)
            floatingView = null
        }
    }
}