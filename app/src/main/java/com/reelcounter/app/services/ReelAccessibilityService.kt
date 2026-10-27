package com.reelcounter.app.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reelcounter.app.data.AppDatabase
import com.reelcounter.app.data.ReelEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReelAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastCountTimestamp: Long = 0
    private var lastPackage: String = ""

    // فترة فلترة التكرار (Debounce): 1400 ملي ثانية لضمان عد كل تمريرة كفيديو واحد
    private val VIDEO_TRANSITION_DEBOUNCE_MS = 1400L

    companion object {
        private const val TAG = "ReelAccessibility"
        const val ACTION_REEL_WATCHED = "com.reelcounter.app.ACTION_REEL_WATCHED"
        const val EXTRA_TOTAL_COUNT = "extra_total_count"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_PACKAGE = "extra_package"

        // حزم التطبيقات المدعومة
        const val PKG_TIKTOK = "com.zhiliaoapp.musically"
        const val PKG_TIKTOK_LITE = "com.ss.android.ugc.trill"
        const val PKG_INSTAGRAM = "com.instagram.android"
        const val PKG_YOUTUBE = "com.google.android.youtube"
        const val PKG_FACEBOOK = "com.facebook.katana"
        const val PKG_FACEBOOK_LITE = "com.facebook.lite"
        const val PKG_SNAPCHAT = "com.snapchat.android"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "ReelCounter Accessibility Engine Connected Successfully!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (!isSupportedPackage(packageName)) return

        val eventType = event.eventType
        val currentTime = SystemClock.uptimeMillis()

        // فحص الفاصل الزمني لمنع الحساب المتكرر
        if (currentTime - lastCountTimestamp < VIDEO_TRANSITION_DEBOUNCE_MS) {
            return
        }

        when (eventType) {
            // 1. حدث تمرير الشاشة (Swipe Up / Scroll)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (isVerticalScrollOrVideoFeed(event, packageName)) {
                    processVideoCount(packageName, currentTime)
                }
            }
            // 2. حدث تغيير محتوى الشاشة (ViewPager2 / RecyclerView) في يوتيوب وإنستجرام
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val className = event.className?.toString() ?: ""
                if (className.contains("ViewPager") || className.contains("RecyclerView") || className.contains("Feed")) {
                    if (isInsideShortsPlayer(packageName, rootInActiveWindow)) {
                        processVideoCount(packageName, currentTime)
                    }
                }
            }
            // 3. حدث اختيار عنصر جديد في التغذية
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                processVideoCount(packageName, currentTime)
            }
        }
    }

    private fun isSupportedPackage(pkg: String): Boolean {
        return pkg == PKG_TIKTOK ||
               pkg == PKG_TIKTOK_LITE ||
               pkg == PKG_INSTAGRAM ||
               pkg == PKG_YOUTUBE ||
               pkg == PKG_FACEBOOK ||
               pkg == PKG_FACEBOOK_LITE ||
               pkg == PKG_SNAPCHAT
    }

    private fun isVerticalScrollOrVideoFeed(event: AccessibilityEvent, pkg: String): Boolean {
        // فحص إذا كان التمرير عمودياً أو كانت الحزمة تيك توك (كل تيك توك هو فيديوهات رأسية)
        if (pkg == PKG_TIKTOK || pkg == PKG_TIKTOK_LITE) return true

        val isVertical = event.scrollDeltaY != 0 || event.scrollY != 0 || event.scrollX == 0
        return isVertical && isInsideShortsPlayer(pkg, rootInActiveWindow)
    }

    private fun isInsideShortsPlayer(pkg: String, rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return true // كإجراء احتياطي

        try {
            when (pkg) {
                PKG_INSTAGRAM -> {
                    val clips = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/clips_video_container")
                    val reelViewer = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/reel_viewer_root")
                    if (clips.isNotEmpty() || reelViewer.isNotEmpty()) return true
                }
                PKG_YOUTUBE -> {
                    val reelFrag = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_player_fragment_container")
                    val reelRecycler = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_recycler")
                    if (reelFrag.isNotEmpty() || reelRecycler.isNotEmpty()) return true
                }
                PKG_FACEBOOK, PKG_FACEBOOK_LITE -> {
                    val fbReel = rootNode.findAccessibilityNodeInfosByViewId("com.facebook.katana:id/fb_shorts_container")
                    if (fbReel.isNotEmpty()) return true
                }
                PKG_SNAPCHAT -> {
                    val spotlight = rootNode.findAccessibilityNodeInfosByViewId("com.snapchat.android:id/spotlight_feed")
                    if (spotlight.isNotEmpty()) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking node hierarchy: ${e.message}")
        }
        return true
    }

    private fun processVideoCount(packageName: String, timestamp: Long) {
        lastCountTimestamp = timestamp
        lastPackage = packageName

        val appName = getFriendlyAppName(packageName)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        Log.d(TAG, "Video watched detected in $appName ($packageName)")

        serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)

            // حفظ المشاهدة في قاعدة بيانات Room المحلية
            val event = ReelEventEntity(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                appName = appName,
                date = todayDate
            )
            db.reelEventDao().insertEvent(event)

            val totalToday = db.reelEventDao().getTodayTotalCount(todayDate)

            // إرسال بث فوري لتحديث الودجت العائم بنظارة الـ VR
            val intent = Intent(FloatingVRGogglesService.ACTION_UPDATE_COUNT).apply {
                putExtra(EXTRA_TOTAL_COUNT, totalToday)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_PACKAGE, packageName)
                setPackage(this@ReelAccessibilityService.packageName)
            }
            sendBroadcast(intent)
        }
    }

    private fun getFriendlyAppName(pkg: String): String {
        return when (pkg) {
            PKG_TIKTOK, PKG_TIKTOK_LITE -> "TikTok"
            PKG_INSTAGRAM -> "Instagram Reels"
            PKG_YOUTUBE -> "YouTube Shorts"
            PKG_FACEBOOK, PKG_FACEBOOK_LITE -> "Facebook Reels"
            PKG_SNAPCHAT -> "Snapchat Spotlight"
            else -> "Short Video"
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }
}