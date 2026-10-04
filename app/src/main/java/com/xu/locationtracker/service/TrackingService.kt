package com.xu.locationtracker.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xu.locationtracker.MainActivity
import com.xu.locationtracker.R
import com.xu.locationtracker.data.AppGraph
import com.xu.locationtracker.data.Prefs
import com.xu.locationtracker.data.TrackerState
import com.xu.locationtracker.data.TrackPoint
import com.xu.locationtracker.data.dayKeyOf
import com.xu.locationtracker.util.fmtDur
import com.xu.locationtracker.util.geoDistM
import com.xu.locationtracker.util.lastFixAgeText
import com.xu.locationtracker.util.trackStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 前台定位记录服务。
 *  - 无 GMS 兼容：原生 LocationManager，GPS + 网络双源
 *  - 保存策略：距上个点位移 >= minDist 才记录（GPS 抖动自然被过滤）；
 *    静止期间每 keepAlive 分钟记一个"在位点"
 *  - 静止 staticAfter 分钟后降频省电，一动恢复
 */
class TrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "tracking"
        private const val NOTIF_ID = 1
        const val ACTION_START = "com.xu.locationtracker.action.START"
        const val ACTION_STOP = "com.xu.locationtracker.action.STOP"
        const val ACTION_PAUSE = "com.xu.locationtracker.action.PAUSE"
        const val ACTION_BOOT = "com.xu.locationtracker.action.BOOT"
        const val ACTION_REFRESH = "com.xu.locationtracker.action.REFRESH"

        fun start(ctx: Context, action: String = ACTION_START) {
            val i = Intent(ctx, TrackingService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // 后台启动被限制时静默失败（华为未开白名单等）
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var locManager: LocationManager? = null
    private var saveJob: Job? = null
    private var currentDay = ""
    private var lastSaved: TrackPoint? = null
    private var lastMovedAt = 0L
    private var staticMode = false
    private var lastNotifAt = 0L
    private var dayLoaded = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onFix(loc)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!AppGraph.isReady) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val today = dayKeyOf(System.currentTimeMillis())
                if (Prefs.mode == Prefs.MODE_AUTO) Prefs.autoStoppedForDay = today
                else Prefs.manualRecording = false
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                // 参数变更：按新参数重启定位订阅
                if (TrackerState.isRecording.value) {
                    startLocationUpdates(
                        if (staticMode) Prefs.staticIntervalMs else Prefs.fastIntervalMs
                    )
                }
                return if (TrackerState.isRecording.value) START_STICKY else START_NOT_STICKY
            }
            ACTION_BOOT -> {
                val today = dayKeyOf(System.currentTimeMillis())
                if (!(Prefs.mode == Prefs.MODE_AUTO && Prefs.autoStoppedForDay != today)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                if (Prefs.mode == Prefs.MODE_MANUAL && !Prefs.manualRecording) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        if (!hasLocationPermission()) {
            stopTracking()
            return
        }
        startForeground(NOTIF_ID, buildNotification("正在启动定位…"))
        TrackerState.isRecording.value = true
        TrackerState.isStatic.value = false
        TrackerState.viewDay.value = null
        staticMode = false
        currentDay = dayKeyOf(System.currentTimeMillis())

        // 恢复当天已有记录（服务被系统重启 / 手动继续）
        if (!dayLoaded) {
            dayLoaded = true
            scope.launch {
                val pts = AppGraph.store.readDay(currentDay)
                TrackerState.points.value = pts
                lastSaved = pts.lastOrNull()
                lastMovedAt = pts.lastOrNull()?.t ?: System.currentTimeMillis()
                if (pts.isNotEmpty()) updateNotification()
            }
        }

        startLocationUpdates(Prefs.fastIntervalMs)
    }

    /**
     * 订阅定位源。
     *  - minDistance 传 0：位移过滤移到应用层（onFix 里判断），保证静止时系统仍按
     *    interval 回调，使"在位心跳"（keepAlive）能按时触发；
     *  - 不再 removeUpdates 后重挂：同一 listener 重复 requestLocationUpdates 会被新参数
     *    取代，避免华为 ROM 上"同一帧摘了又挂"导致订阅假死（曾实测 32 分钟无回调）。
     */
    private fun startLocationUpdates(intervalMs: Long) {
        val lm = locManager ?: return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, intervalMs, 0f, listener)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun onFix(loc: Location) {
        TrackerState.lastFixAt.value = System.currentTimeMillis()
        if (!loc.hasAccuracy() || loc.accuracy <= 0f || loc.accuracy > Prefs.accFM) {
            // 定位收到了但不可用：也刷新通知，让用户看出"正在收到定位（未被采纳）"
            updateNotification(throttle = true)
            return
        }
        val now = System.currentTimeMillis()

        // 跨天切换
        val day = dayKeyOf(now)
        if (day != currentDay) {
            currentDay = day
            TrackerState.points.value = emptyList()
            lastSaved = null
        }

        val p = TrackPoint(now, loc.latitude, loc.longitude, loc.accuracy, if (loc.hasSpeed()) loc.speed else 0f)
        val last = lastSaved
        if (last == null) {
            savePoint(p)
            return
        }

        val dt = now - last.t
        val dist = geoDistM(last.lat to last.lon, p.lat to p.lon)
        // 双源去重：4 秒内 3 米内视为同一位置的不同来源
        if (SavePolicy.isDuplicate(dt, dist)) return

        // 保存策略：移动超过阈值 或 到达在位心跳间隔
        if (SavePolicy.shouldSave(dist, dt, Prefs.minDistF, Prefs.keepAliveMs)) {
            savePoint(p)
        }

        // 静止判定与频率切换
        if (dist >= Prefs.minDistF) {
            lastMovedAt = now
            if (staticMode) {
                staticMode = false
                TrackerState.isStatic.value = false
                startLocationUpdates(Prefs.fastIntervalMs)
                updateNotification()
            }
        } else if (!staticMode && SavePolicy.isStatic(dist, Prefs.minDistF, now, lastMovedAt, Prefs.staticAfterMs)) {
            staticMode = true
            TrackerState.isStatic.value = true
            startLocationUpdates(Prefs.staticIntervalMs)
            updateNotification()
        }
    }

    private fun savePoint(p: TrackPoint) {
        lastSaved = p
        TrackerState.points.value = TrackerState.points.value + p
        saveJob?.cancel()
        saveJob = scope.launch { AppGraph.store.append(p) }
        updateNotification(throttle = true)
    }

    private fun stopTracking() {
        runCatching { locManager?.removeUpdates(listener) }
        TrackerState.isRecording.value = false
        TrackerState.isStatic.value = false
        dayLoaded = false
        scope.cancel()
        AppGraph.store.closeAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // stopService()/系统回收等未走 ACTION_STOP 的路径兜底清理
        if (TrackerState.isRecording.value) {
            runCatching { locManager?.removeUpdates(listener) }
            TrackerState.isRecording.value = false
            TrackerState.isStatic.value = false
            dayLoaded = false
            scope.cancel()
        }
        AppGraph.store.closeAll()
    }

    // ---------- 通知 ----------

    private fun updateNotification(throttle: Boolean = false) {
        val now = System.currentTimeMillis()
        if (throttle && now - lastNotifAt < 15_000 && TrackerState.isRecording.value) return
        lastNotifAt = now
        val (dist, dur, count) = trackStats(TrackerState.points.value)
        val status = when {
            !TrackerState.isRecording.value -> "已停止"
            TrackerState.isStatic.value -> "记录中 · 静止省电"
            else -> "记录中"
        }
        val text = buildString {
            append(status).append(" · ")
            append(String.format(Locale.US, "%.1f km", dist / 1000.0))
            if (dur > 0) {
                append(" · ")
                append(fmtDur(dur))
            }
            append(" · ").append(count).append(" 点")
            val lastFix = TrackerState.lastFixAt.value
            if (lastFix > 0) {
                val ageSec = (System.currentTimeMillis() - lastFix) / 1000
                append(" · 上次定位 ").append(lastFixAgeText(ageSec))
                if (ageSec >= 60) append(" ⚠")
            }
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        // 超过 60 秒没收到定位：通知底色变灰（正常为品牌蓝）
        val lastFix = TrackerState.lastFixAt.value
        val stale = lastFix > 0 && System.currentTimeMillis() - lastFix > 60_000
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setColor(if (stale) Color.GRAY else 0xFF1565D8.toInt())
            .setContentTitle("行程轨迹")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
            .addAction(0, "结束", stopPi)
        return builder.build()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}