package com.reelcounter.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelcounter.app.R
import com.reelcounter.app.data.AppDatabase
import com.reelcounter.app.services.FloatingVRGogglesService
import com.reelcounter.app.utils.PermissionHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // متغيرات حالة الصلاحيات
    private val isAccessibilityGranted = mutableStateOf(false)
    private val isOverlayGranted = mutableStateOf(false)
    private val isUsageStatsGranted = mutableStateOf(false)
    private val isBatteryExempted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePermissionStates()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF070B14)
                ) {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    val db = remember { AppDatabase.getInstance(context) }
                    val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                    var todayCount by remember { mutableStateOf(0) }
                    var appStats by remember { mutableStateOf<List<AppStatItem>>(emptyList()) }
                    var isFloatingServiceRunning by remember { mutableStateOf(true) }

                    // متابعة قاعدة البيانات مباشرة عبر Flow
                    LaunchedEffect(Unit) {
                        launch {
                            db.reelEventDao().observeTodayTotalCount(todayDate).collectLatest { count ->
                                todayCount = count
                            }
                        }
                        launch {
                            db.reelEventDao().observeTodayBreakdown(todayDate).collectLatest { list ->
                                appStats = list.map { b ->
                                    val color = when (b.packageName) {
                                        "com.zhiliaoapp.musically" -> Color(0xFF00F2FE)
                                        "com.instagram.android" -> Color(0xFFE1306C)
                                        "com.google.android.youtube" -> Color(0xFFFF0000)
                                        "com.facebook.katana" -> Color(0xFF1877F2)
                                        else -> Color(0xFFFFFC00)
                                    }
                                    AppStatItem(b.appName, b.packageName, b.count, color)
                                }
                            }
                        }
                    }

                    // هل كل الصلاحيات الأساسية ممنوحة؟
                    val allPermissionsGranted = isAccessibilityGranted.value && isOverlayGranted.value

                    Column(modifier = Modifier.fillMaxSize()) {
                        // شريط العنوان العلوي
                        ReelTopBar()

                        if (!allPermissionsGranted) {
                            // معالج الصلاحيات المباشر عند أول تشغيل
                            PermissionsOnboardingCard(
                                isAccessibility = isAccessibilityGranted.value,
                                isOverlay = isOverlayGranted.value,
                                isUsageStats = isUsageStatsGranted.value,
                                onOpenAccessibility = { PermissionHelper.openAccessibilitySettings(this@MainActivity) },
                                onOpenRestrictedSettings = { PermissionHelper.openAppDetailsSettings(this@MainActivity) },
                                onOpenOverlay = { PermissionHelper.openOverlaySettings(this@MainActivity) },
                                onOpenUsageStats = { PermissionHelper.openUsageStatsSettings(this@MainActivity) }
                            )
                        } else {
                            // لوحة الإحصائيات الكاملة
                            DashboardContent(
                                todayCount = todayCount,
                                dailyGoal = 100,
                                appStats = appStats,
                                isFloatingActive = isFloatingServiceRunning,
                                onToggleFloating = { enable ->
                                    isFloatingServiceRunning = enable
                                    val intent = Intent(this@MainActivity, FloatingVRGogglesService::class.java)
                                    if (enable) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            startForegroundService(intent)
                                        } else {
                                            startService(intent)
                                        }
                                    } else {
                                        stopService(intent)
                                    }
                                },
                                onResetToday = {
                                    scope.launch {
                                        db.reelEventDao().clearTodayEvents(todayDate)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // فحص الصلاحيات تلقائياً بمجرد عودة المستخدم من شاشة الإعدادات
        updatePermissionStates()

        // تشغيل خدمة نظارة الـ VR تلقائياً فور منح الصلاحيات
        if (isAccessibilityGranted.value && isOverlayGranted.value) {
            val intent = Intent(this, FloatingVRGogglesService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun updatePermissionStates() {
        isAccessibilityGranted.value = PermissionHelper.isAccessibilityServiceEnabled(this)
        isOverlayGranted.value = PermissionHelper.canDrawOverlays(this)
        isUsageStatsGranted.value = PermissionHelper.hasUsageStatsPermission(this)
        isBatteryExempted.value = PermissionHelper.isIgnoringBatteryOptimizations(this)
    }
}

// نموذج عرض إحصائيات التطبيق
data class AppStatItem(
    val appName: String,
    val packageName: String,
    val count: Int,
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReelTopBar() {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF00F2FE).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = Color(0xFF00F2FE),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "VR Goggles Floating Counter",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF0F172A)
        )
    )
}

/**
 * معالج الصلاحيات المباشر (Automatic Permission Setup Wizard)
 * يوجه المستخدم مباشرة للشاشات بنقرة واحدة ويحل حظر أندرويد 13/14
 */
@Composable
fun PermissionsOnboardingCard(
    isAccessibility: Boolean,
    isOverlay: Boolean,
    isUsageStats: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenRestrictedSettings: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenUsageStats: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "⚡ مطلوب تفعيل الصلاحيات لمرة واحدة",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "لكي تتمكن نظارة الـ VR من الظهور فوق التطبيقات وعد الفيديوهات بدقة، يرجى النقر على الأزرار بالأسفل لتفعيل الصلاحيات مباشرة:",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // 1. صلاحية إمكانية الوصول Accessibility
        item {
            PermissionStepRow(
                stepNumber = "1",
                title = "خدمة رصد الفيديوهات (Accessibility)",
                desc = "لقراءة تمرير الفيديوهات في تيك توك وريلز وشورتس وسناب شات.",
                isGranted = isAccessibility,
                buttonText = if (isAccessibility) "مفعلة ✓" else "تفعيل إمكانية الوصول",
                onAction = onOpenAccessibility
            )
        }

        // تنبيه مهم لأندرويد 13 و 14: فك الإعدادات المقيدة
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF332005)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFBBF24))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "هام لنظام أندرويد 13 و 14 (الإعدادات المقيدة)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFBBF24)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "إذا ظهرت لك رسالة 'تم حظر هذا الإعداد لحماية جهازك' عند تفعيل إمكانية الوصول: اضغط الزر أدناه للدخول لصفحة معلومات التطبيق، ثم اضغط على الثلاث نقاط (⋮) أعلى اليمين واختر 'السماح بالإعدادات المقيدة'.",
                        fontSize = 12.sp,
                        color = Color(0xFFFDE68A),
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenRestrictedSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("فتح معلومات التطبيق (السماح بالإعدادات)", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        // 2. صلاحية النافذة العائمة Overlay
        item {
            PermissionStepRow(
                stepNumber = "2",
                title = "الظهور فوق التطبيقات (Overlay)",
                desc = "لإظهار نظارة الـ VR العائمة والعداد الحي فوق كل التطبيقات.",
                isGranted = isOverlay,
                buttonText = if (isOverlay) "مفعلة ✓" else "تفعيل الأيقونة العائمة",
                onAction = onOpenOverlay
            )
        }

        // 3. صلاحية إحصائيات الاستخدام Usage Stats
        item {
            PermissionStepRow(
                stepNumber = "3",
                title = "إحصائيات الاستخدام (اختياري للتعرف الدقيق)",
                desc = "لمعرفة اسم التطبيق المفتوح في الواجهة بدقة متناهية.",
                isGranted = isUsageStats,
                buttonText = if (isUsageStats) "مفعلة ✓" else "منح الصلاحية",
                onAction = onOpenUsageStats
            )
        }
    }
}

@Composable
fun PermissionStepRow(
    stepNumber: String,
    title: String,
    desc: String,
    isGranted: Boolean,
    buttonText: String,
    onAction: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) Color(0xFF0F291E) else Color(0xFF1E293B)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isGranted) Color(0xFF10B981) else Color(0xFF00F2FE)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isGranted) "✓" else stepNumber,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = desc, fontSize = 12.sp, color = Color(0xFF94A3B8))
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) Color(0xFF059669) else Color(0xFF00F2FE),
                    contentColor = if (isGranted) Color.White else Color.Black
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(text = buttonText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun DashboardContent(
    todayCount: Int,
    dailyGoal: Int,
    appStats: List<AppStatItem>,
    isFloatingActive: Boolean,
    onToggleFloating: (Boolean) -> Unit,
    onResetToday: () -> Unit
) {
    val estimatedMinutes = (todayCount * 0.45).toInt()
    val progress = (todayCount.toFloat() / dailyGoal).coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // بطاقة العداد الرئيسية
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.today_shorts_watched),
                        fontSize = 14.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(170.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(170.dp),
                            strokeWidth = 14.dp,
                            color = if (todayCount >= dailyGoal) Color(0xFFFF3B30) else Color(0xFF00F2FE),
                            trackColor = Color(0xFF1E293B)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$todayCount",
                                fontSize = 48.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = stringResource(R.string.video_unit),
                                fontSize = 14.sp,
                                color = Color(0xFF00F2FE)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("الوقت المستغرق", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text("$estimatedMinutes دقيقة", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("الحد اليومي", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text("$dailyGoal فيديو", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        // مفتاح تفعيل نظارة الـ VR العائمة
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "نظارة الـ VR العائمة (Floating Overlay)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Text(
                            text = "ظهور الأيقونة والعداد المباشر فوق تيك توك وريلز",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(checked = isFloatingActive, onCheckedChange = onToggleFloating)
                }
            }
        }

        // تفاصيل التطبيقات
        item {
            Text(
                text = "توزيع المشاهدات حسب التطبيقات",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White
            )
        }

        items(appStats) { item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(item.color)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = item.appName, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    Text(
                        text = "${item.count} فيديو",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00F2FE)
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}