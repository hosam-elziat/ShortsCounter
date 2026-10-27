package com.reelcounter.app.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
    private var lastVideoSignature: String = ""

    // فترة فلترة التكرار (Debounce): 850 ملي ثانية لمنع العد المزدوج للتمريرة الواحدة
    private val VIDEO_TRANSITION_DEBOUNCE_MS = 850L

    companion object {
        private const val TAG = "ReelAccessibility"
        const val ACTION_REEL_WATCHED = "com.reelcounter.app.ACTION_REEL_WATCHED"
        const val ACTION_MANUAL_TEST_COUNT = "com.reelcounter.app.ACTION_MANUAL_TEST_COUNT"
        const val EXTRA_TOTAL_COUNT = "extra_total_count"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_PACKAGE = "extra_package"

        // باقات التطبيقات المدعومة عالمياً ومحلياً
        const val PKG_TIKTOK = "com.zhiliaoapp.musically"
        const val PKG_TIKTOK_LITE = "com.ss.android.ugc.trill"
        const val PKG_TIKTOK_AWEME = "com.ss.android.ugc.aweme"
        const val PKG_INSTAGRAM = "com.instagram.android"
        const val PKG_INSTAGRAM_LITE = "com.instagram.lite"
        const val PKG_YOUTUBE = "com.google.android.youtube"
        const val PKG_FACEBOOK = "com.facebook.katana"
        const val PKG_FACEBOOK_LITE = "com.facebook.lite"
        const val PKG_SNAPCHAT = "com.snapchat.android"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "ReelCounter Accessibility Engine Connected & Monitoring Started!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (!isSupportedPackage(packageName)) return

        val eventType = event.eventType
        val currentTime = SystemClock.uptimeMillis()

        // 1. فحص إذا كان التطبيق يعرض فيديوهات قصيرة
        val isShorts = isCurrentScreenShortVideo(packageName, event)
        if (!isShorts) return

        // 2. فحص الفاصل الزمني لمنع الحساب المتكرر للتمريرة الواحدة
        val timeSinceLast = currentTime - lastCountTimestamp
        if (timeSinceLast < VIDEO_TRANSITION_DEBOUNCE_MS) {
            return
        }

        // 3. استخراج معرّف أو بصمة الفيديو الحالي إن وجدت
        val currentSignature = extractVideoSignature(event)
        val hasSignatureChanged = currentSignature.isNotBlank() && currentSignature != lastVideoSignature

        // 4. رصد الانتقال عبر 3 محاور:
        // أ) حدث التمرير الفعلي (Scroll / Swipe Up)
        val isScrollEvent = eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        // ب) حدث تغير الصفحة أو شجرة العرض (ViewPager2 / RecyclerView)
        val isWindowContentChanged = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0 ||
                 event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0)
        // ج) حدث اختيار عنصر جديد (View Selected)
        val isViewSelected = eventType == AccessibilityEvent.TYPE_VIEW_SELECTED

        if (hasSignatureChanged || isScrollEvent || isViewSelected || (isWindowContentChanged && timeSinceLast > 1200L)) {
            if (currentSignature.isNotBlank()) {
                lastVideoSignature = currentSignature
            }
            processVideoCount(packageName, currentTime)
        }
    }

    private fun isSupportedPackage(pkg: String): Boolean {
        return pkg.contains("musically") ||
               pkg.contains("trill") ||
               pkg.contains("aweme") ||
               pkg.contains("instagram") ||
               pkg.contains("youtube") ||
               pkg.contains("facebook") ||
               pkg.contains("snapchat")
    }

    private fun isCurrentScreenShortVideo(pkg: String, event: AccessibilityEvent): Boolean {
        // تيك توك: كامل واجهة التطبيق عبارة عن مقاطع ريلز قصيرة
        if (pkg.contains("musically") || pkg.contains("trill") || pkg.contains("aweme")) {
            return true
        }

        val rootNode = try { rootInActiveWindow ?: event.source } catch (e: Exception) { null }
        if (rootNode == null) return true // إجراء احتياطي كي لا يتم تفويت أي فيديو

        return try {
            when {
                pkg.contains("youtube") -> {
                    // فحص يوتيوب شورتس عبر النصوص أو معرّفات مشغل شورتس
                    hasNodeWithId(rootNode, "reel") ||
                    hasNodeWithId(rootNode, "shorts") ||
                    hasNodeWithText(rootNode, "Shorts") ||
                    hasNodeWithText(rootNode, "شورتس") ||
                    hasNodeWithText(rootNode, "Remix") ||
                    hasNodeWithText(rootNode, "Sound") ||
                    hasNodeWithText(rootNode, "اشتراك") ||
                    hasNodeWithText(rootNode, "Subscribe")
                }
                pkg.contains("instagram") -> {
                    // فحص إنستجرام ريلز عبر الحاوية أو تبويب الريلز
                    hasNodeWithId(rootNode, "clips") ||
                    hasNodeWithId(rootNode, "reel") ||
                    hasNodeWithId(rootNode, "video_container") ||
                    hasNodeWithText(rootNode, "Reels") ||
                    hasNodeWithText(rootNode, "ريلز") ||
                    hasNodeWithText(rootNode, "Audio")
                }
                pkg.contains("facebook") -> {
                    hasNodeWithId(rootNode, "reel") ||
                    hasNodeWithId(rootNode, "fb_shorts") ||
                    hasNodeWithText(rootNode, "Reels") ||
                    hasNodeWithText(rootNode, "ريلز")
                }
                pkg.contains("snapchat") -> {
                    hasNodeWithId(rootNode, "spotlight") ||
                    hasNodeWithText(rootNode, "Spotlight")
                }
                else -> true
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun hasNodeWithId(root: AccessibilityNodeInfo, partialId: String): Boolean {
        return try {
            val list = root.findAccessibilityNodeInfosByViewId(partialId)
            if (list.isNotEmpty()) return true
            for (i in 0 until minOf(root.childCount, 6)) {
                val child = root.getChild(i) ?: continue
                val childId = child.viewIdResourceName ?: ""
                if (childId.contains(partialId, ignoreCase = true)) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun hasNodeWithText(root: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val list = root.findAccessibilityNodeInfosByText(text)
            list.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun extractVideoSignature(event: AccessibilityEvent): String {
        return try {
            val root = rootInActiveWindow ?: event.source ?: return ""
            val sb = StringBuilder()
            for (i in 0 until minOf(root.childCount, 8)) {
                val child = root.getChild(i) ?: continue
                val text = child.text?.toString() ?: child.contentDescription?.toString() ?: ""
                if (text.length in 3..40) {
                    sb.append(text).append("#")
                }
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    fun processVideoCount(packageName: String, timestamp: Long) {
        lastCountTimestamp = timestamp
        lastPackage = packageName

        val appName = getFriendlyAppName(packageName)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        Log.d(TAG, "Video counted successfully in $appName ($packageName)")

        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)

                // 1. تسجيل المشاهدة في قاعدة بيانات Room المحلية
                val event = ReelEventEntity(
                    timestamp = System.currentTimeMillis(),
                    packageName = packageName,
                    appName = appName,
                    date = todayDate
                )
                db.reelEventDao().insertEvent(event)

                val totalToday = db.reelEventDao().getTodayTotalCount(todayDate)

                // 2. إرسال بث فوري لتحديث نظارة الـ VR العائمة والشاشة
                val intent = Intent(FloatingVRGogglesService.ACTION_UPDATE_COUNT).apply {
                    putExtra(EXTRA_TOTAL_COUNT, totalToday)
                    putExtra(EXTRA_APP_NAME, appName)
                    putExtra(EXTRA_PACKAGE, packageName)
                    setPackage(this@ReelAccessibilityService.packageName)
                }
                sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert count: ${e.message}")
            }
        }
    }

    private fun getFriendlyAppName(pkg: String): String {
        return when {
            pkg.contains("musically") || pkg.contains("trill") || pkg.contains("aweme") -> "TikTok"
            pkg.contains("instagram") -> "Instagram Reels"
            pkg.contains("youtube") -> "YouTube Shorts"
            pkg.contains("facebook") -> "Facebook Reels"
            pkg.contains("snapchat") -> "Snapchat Spotlight"
            else -> "Short Video"
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }
}