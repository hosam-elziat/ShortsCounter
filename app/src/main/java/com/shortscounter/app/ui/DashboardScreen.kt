package com.shortscounter.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortscounter.app.R
import com.shortscounter.app.services.FloatingWidgetService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    todayCount: Int,
    dailyGoal: Int,
    appStats: List<AppDisplayStat>,
    isFloatingWidgetRunning: Boolean,
    onToggleFloatingWidget: (Boolean) -> Unit,
    onResetToday: () -> Unit
) {
    val context = LocalContext.current
    val progress = (todayCount.toFloat() / dailyGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")
    val estimatedMinutes = (todayCount * 24) / 60 // بمتوسط 24 ثانية لكل فيديو

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onResetToday) {
                        Icon(Icons.Default.Refresh, contentDescription = "تصفير")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                // بطاقة العداد الرئيسية مع نظارة الـ VR
                MainCounterCard(
                    todayCount = todayCount,
                    dailyGoal = dailyGoal,
                    progress = animatedProgress,
                    estimatedMinutes = estimatedMinutes
                )
            }

            // بطاقة التحكم في الأيقونة العائمة
            item {
                FloatingWidgetControlCard(
                    isRunning = isFloatingWidgetRunning,
                    onToggle = { enable ->
                        if (enable && !Settings.canDrawOverlays(context)) {
                            // توجيه المستخدم لمنح إذن الظهور فوق التطبيقات
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            onToggleFloatingWidget(enable)
                        }
                    }
                )
            }

            // عنوان قسم توزيع التطبيقات
            item {
                Text(
                    text = stringResource(R.string.apps_breakdown_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // عناصر التطبيقات (تيك توك، ريلز، شورتس، سناب شات)
            items(appStats) { stat ->
                AppStatRow(stat = stat, totalCount = todayCount)
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun MainCounterCard(
    todayCount: Int,
    dailyGoal: Int,
    progress: Float,
    estimatedMinutes: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.today_shorts_watched),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // دائرة العداد الرقمي
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(170.dp)
            ) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(170.dp),
                    strokeWidth = 14.dp,
                    color = if (todayCount >= dailyGoal) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$todayCount",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stringResource(R.string.video_unit),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // شريط إحصائيات سريعة: الوقت الضائع والهدف
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatPill(
                    icon = Icons.Default.Timer,
                    label = stringResource(R.string.time_spent),
                    value = "$estimatedMinutes ${stringResource(R.string.minute_unit)}"
                )
                StatPill(
                    icon = Icons.Default.Flag,
                    label = stringResource(R.string.daily_limit_goal),
                    value = "$dailyGoal ${stringResource(R.string.video_unit)}"
                )
            }
        }
    }
}

@Composable
fun FloatingWidgetControlCard(
    isRunning: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.RemoveRedEye,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.floating_vr_widget_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = stringResource(R.string.floating_widget_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = isRunning,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
fun AppStatRow(stat: AppDisplayStat, totalCount: Int) {
    val percentage = if (totalCount > 0) (stat.count.toFloat() / totalCount * 100).toInt() else 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(stat.color)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stat.appName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Text(
                    text = "${stat.count} (${percentage}%)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (totalCount > 0) stat.count.toFloat() / totalCount else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = stat.color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
fun StatPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = label, fontSize = 11.sp)
        }
        Text(text = value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

data class AppDisplayStat(
    val appName: String,
    val packageName: String,
    val count: Int,
    val color: Color
)