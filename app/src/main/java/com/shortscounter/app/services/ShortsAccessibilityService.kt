package com.shortscounter.app.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shortscounter.app.data.AppDatabase
import com.shortscounter.app.data.VideoRecordEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * خدمة إمكانية الوصول الذكية (Smart Accessibility Service)
 * تراقب تطبيقات الفيديوهات القصيرة وتلتقط لحظات الانتقال بين الفيديوهات بدقة.
 */
class ShortsAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastScrollTimestamp: Long = 0
    private var lastPackage: String = ""

    // الحد الأدنى بين كل سكرول لتجنب زيادة العداد مرتين في حركة واحدة (Debounce Time = 700ms)
    private val SCROLL_DEBOUNCE_MS = 700L

    companion object {
        private const val TAG = "ShortsAccessibility"
        const val ACTION_COUNTER_UPDATED = "com.shortscounter.app.COUNTER_UPDATED"
        const val EXTRA_COUNT = "extra_count"
        const val EXTRA_PACKAGE = "extra_package"

        // تعريف الحزم المستهدفة
        const val PKG_TIKTOK = "com.zhiliaoapp.musically"
        const val PKG_TIKTOK_LITE = "com.ss.android.ugc.trill"
        const val PKG_INSTAGRAM = "com.instagram.android"
        const val PKG_YOUTUBE = "com.google.android.youtube"
        const val PKG_FACEBOOK = "com.facebook.katana"
        const val PKG_SNAPCHAT = "com.snapchat.android"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "ShortsAccessibilityService connected and actively monitoring")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val eventType = event.eventType

        // التحقق من أن الحدث قادم من إحدى التطبيقات المستهدفة
        if (!isTargetPackage(packageName)) return

        // التحقق من نوع الحدث: تمرير (Scroll) أو تغيير حالة النافذة
        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleScrollEvent(packageName, event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // فحص دقيق لعناصر الشاشة في حال استخدام أجهزة تطلق تغير المحتوى
                checkShortsContentTransition(packageName, event)
            }
        }
    }

    private fun isTargetPackage(pkg: String): Boolean {
        return pkg == PKG_TIKTOK ||
               pkg == PKG_TIKTOK_LITE ||
               pkg == PKG_INSTAGRAM ||
               pkg == PKG_YOUTUBE ||
               pkg == PKG_FACEBOOK ||
               pkg == PKG_SNAPCHAT
    }

    private fun handleScrollEvent(packageName: String, event: AccessibilityEvent) {
        val currentTime = SystemClock.uptimeMillis()

        // منع الحساب المتكرر للـ Fling الواحد
        if (currentTime - lastScrollTimestamp < SCROLL_DEBOUNCE_MS) {
            return
        }

        // التحقق من أن التمرير كان رأسياً (Vertical) كما هو الحال في تيك توك وريلز
        val isVerticalScroll = event.scrollDeltaY != 0 || event.scrollY != 0 || event.scrollX == 0

        // فحص إضافي لشجرة العناصر للتحقق من أن المستخدم فعلاً داخل شاشة الريلز/الشورتس
        if (isInsideShortsSection(packageName, rootInActiveWindow)) {
            lastScrollTimestamp = currentTime
            lastPackage = packageName
            onNewVideoWatched(packageName)
        }
    }

    private fun checkShortsContentTransition(packageName: String, event: AccessibilityEvent) {
        // فحص ذكي خاص بـ YouTube Shorts وتيك توك عند انتهاء وتمرير الفيديو
        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastScrollTimestamp < SCROLL_DEBOUNCE_MS) return

        val className = event.className?.toString() ?: ""
        if (className.contains("ViewPager") || className.contains("RecyclerView") || className.contains("Feed")) {
            if (isInsideShortsSection(packageName, rootInActiveWindow)) {
                lastScrollTimestamp = currentTime
                onNewVideoWatched(packageName)
            }
        }
    }

    /**
     * فحص شجرة العناصر لتأكيد أن المستخدم داخل مشغل الفيديوهات القصيرة وليس الصفحة العامة
     */
    private fun isInsideShortsSection(pkg: String, rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return true // كاحتياط نعتبره صحيحاً إذا كانت الحزمة مستهدفة

        try {
            when (pkg) {
                PKG_INSTAGRAM -> {
                    // Instagram Reels عادة تحتوي على المعرفات التالية
                    val reelsNodes = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/reel_viewer_root")
                    val clipsNodes = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/clips_video_container")
                    if (reelsNodes.isNotEmpty() || clipsNodes.isNotEmpty()) return true
                }
                PKG_YOUTUBE -> {
                    // YouTube Shorts تحتوي على حاوية reel_player
                    val shortsNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_player_fragment_container")
                    val playerNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_recycler")
                    if (shortsNodes.isNotEmpty() || playerNodes.isNotEmpty()) return true
                }
                PKG_TIKTOK, PKG_TIKTOK_LITE -> {
                    // تطبيق تيك توك بالكامل عبارة عن فيديوهات قصيرة
                    return true
                }
                PKG_SNAPCHAT -> {
                    val spotlightNodes = rootNode.findAccessibilityNodeInfosByViewId("com.snapchat.android:id/spotlight_feed")
                    if (spotlightNodes.isNotEmpty()) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting node hierarchy: ${e.message}")
        }
        return true
    }

    private fun onNewVideoWatched(packageName: String) {
        val appName = getFriendlyAppName(packageName)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        Log.d(TAG, "New Short Watched on $appName [$packageName]")

        // 1. تسجيل المشاهدة في قاعدة بيانات Room المحلية
        serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val record = VideoRecordEntity(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                appName = appName,
                date = todayDate
            )
            db.videoRecordDao().insertRecord(record)

            val totalToday = db.videoRecordDao().getTodayTotalCount(todayDate)

            // 2. إرسال البث المباشر للخدمة العائمة لتحديث العداد فورياً
            val updateIntent = Intent(FloatingWidgetService.ACTION_UPDATE_COUNT).apply {
                putExtra(EXTRA_COUNT, totalToday)
                putExtra(EXTRA_PACKAGE, packageName)
                setPackage(this@ShortsAccessibilityService.packageName)
            }
            sendBroadcast(updateIntent)
        }
    }

    private fun getFriendlyAppName(pkg: String): String {
        return when (pkg) {
            PKG_TIKTOK, PKG_TIKTOK_LITE -> "TikTok"
            PKG_INSTAGRAM -> "Instagram Reels"
            PKG_YOUTUBE -> "YouTube Shorts"
            PKG_FACEBOOK -> "Facebook Reels"
            PKG_SNAPCHAT -> "Snapchat Spotlight"
            else -> "Social Media"
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ShortsAccessibilityService interrupted")
    }
}