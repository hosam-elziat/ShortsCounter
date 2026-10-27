package com.shortscounter.app.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.shortscounter.app.R
import com.shortscounter.app.data.AppDatabase
import com.shortscounter.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * خدمة الأيقونة العائمة (Floating VR Headset Widget Service)
 * تعمل كـ Foreground Service لضمان عدم إغلاقها من النظام وتظل ظاهرة فوق التطبيقات.
 */
class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private lateinit var tvCounter: TextView
    private lateinit var ivVrIcon: ImageView
    private var currentCount = 0
    private var dailyGoal = 100

    private val CHANNEL_ID = "ShortsCounter_Foreground_Channel"
    private val NOTIFICATION_ID = 1001

    companion object {
        const val ACTION_UPDATE_COUNT = "com.shortscounter.app.UPDATE_COUNT"
        const val ACTION_RESET_COUNT = "com.shortscounter.app.RESET_COUNT"
        const val ACTION_CLOSE_WIDGET = "com.shortscounter.app.CLOSE_WIDGET"
    }

    // مستقبل لتلقي تحديثات العداد من خدمة الـ Accessibility
    private val counterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_COUNT -> {
                    val count = intent.getIntExtra(ShortsAccessibilityService.EXTRA_COUNT, -1)
                    if (count >= 0) {
                        updateWidgetCount(count)
                    } else {
                        fetchLatestCountFromDb()
                    }
                }
                ACTION_RESET_COUNT -> {
                    updateWidgetCount(0)
                }
                ACTION_CLOSE_WIDGET -> {
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("0 فيديوهات اليوم"))

        registerReceiver(
            counterReceiver,
            IntentFilter().apply {
                addAction(ACTION_UPDATE_COUNT)
                addAction(ACTION_RESET_COUNT)
                addAction(ACTION_CLOSE_WIDGET)
            },
            RECEIVER_NOT_EXPORTED
        )

        initFloatingWidget()
        fetchLatestCountFromDb()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun initFloatingWidget() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // تصميم الودجت العائم عبر الـ XML أو البرمجة المباشرة
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_vr_widget, null)

        tvCounter = floatingView!!.findViewById(R.id.tv_counter)
        ivVrIcon = floatingView!!.findViewById(R.id.iv_vr_headset)

        // إعداد معايير النافذة العائمة (TYPE_APPLICATION_OVERLAY لنظام Android 8.0+)
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        windowManager.addView(floatingView, params)

        // تمكين حركة السحب باللمس والسلاسة عند الحواف
        setupTouchListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isClick = false
                    }

                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        // عند النقر على الأيقونة العائمة -> فتح لوحة الإحصائيات Dashboard
                        openMainActivity()
                    } else {
                        // استقرار الودجت والتصاقه بالجانب الأقرب (Snap to edge)
                        snapToNearestEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToNearestEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val middle = screenWidth / 2
        val targetX = if (params.x + (floatingView?.width ?: 0) / 2 < middle) 20 else screenWidth - (floatingView?.width ?: 120) - 20
        params.x = targetX
        windowManager.updateViewLayout(floatingView, params)
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    fun updateWidgetCount(count: Int) {
        currentCount = count
        tvCounter.text = count.toString()

        // تأثير بصري عند الوصول للحد اليومي (تحويل اللون للأحمر واهتزاز خفيف)
        if (currentCount >= dailyGoal) {
            tvCounter.setTextColor(Color.parseColor("#FF4D4D"))
            triggerHapticFeedback()
        } else {
            tvCounter.setTextColor(Color.WHITE)
        }

        // تحديث إشعار الخدمة الأمامية
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification("$count فيديو تم مشاهدته اليوم"))
    }

    private fun triggerHapticFeedback() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(120)
        }
    }

    private fun fetchLatestCountFromDb() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(applicationContext)
            val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val count = db.videoRecordDao().getTodayTotalCount(todayDate)
            withContext(Dispatchers.Main) {
                updateWidgetCount(count)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "عداد الفيديوهات القصيرة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة المراقبة والأيقونة العائمة لعداد الفيديوهات القصيرة"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("عداد الفيديوهات القصيرة الذكي")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_vr_glasses)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(counterReceiver)
        if (floatingView != null) {
            windowManager.removeView(floatingView)
        }
    }
}