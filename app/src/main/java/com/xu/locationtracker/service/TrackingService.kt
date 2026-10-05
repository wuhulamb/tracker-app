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
import android.util.Log
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 前台定位记录服务。
 *  - 无 GMS 兼容：原生 LocationManager，GPS + 网络双源
 *  - 保存策略：距上个点位移 >= minDist 才记录（GPS 抖动自然被过滤）；
 *    静止期间每 keepAlive 分钟记一个"在位点"
 *  - 静止 staticAfter 分钟后降频省电，一动恢复
 */
class TrackingService : Service() {

    companion object {
        private const val TAG = "LocationTracker"
        const val CHANNEL_ID = "tracking"
        private const val NOTIF_ID = 1
        const val ACTION_START = "com.xu.locationtracker.action.START"
        const val ACTION_STOP = "com.xu.locationtracker.action.STOP"
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

    /** 落盘队列：单消费者严格 FIFO 串行写文件，保证每笔必写、顺序与生成一致 */
    private var saveQueue: Channel<TrackPoint> = Channel(Channel.UNLIMITED)
    private var locManager: LocationManager? = null
    private var saveJob: Job? = null
    private var currentDay = ""
    private var lastSaved: TrackPoint? = null
    private var lastMovedAt = 0L
    private var lastGpsFixAt = 0L
    /** 最近一次通过精度过滤的"可用 GPS fix"时间（判定无信号进静止） */
    private var lastGoodGpsAt = 0L
    private var staticMode = false
    private var lastNotifAt = 0L
    private var dayLoaded = false
    private var blackoutJob: Job? = null

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
        // 重复 START（UI 连点 / START_STICKY 重投 / 开机广播与自启叠加）：已在记录且当天已恢复，
        // 不重跑恢复与订阅，否则会把静止降频重置回高频采样。
        // 仍刷一次通知：刷新同 id 的通知可满足 startForegroundService 对 startForeground 的超时要求。
        if (TrackerState.isRecording.value && dayLoaded) {
            Log.d(TAG, "duplicate start, keep current session (staticMode=$staticMode)")
            updateNotification()
            return
        }
        startForeground(NOTIF_ID, buildNotification("正在启动定位…"))
        TrackerState.isRecording.value = true
        TrackerState.isStatic.value = false
        TrackerState.viewDay.value = null
        staticMode = false
        lastGoodGpsAt = System.currentTimeMillis()
        currentDay = dayKeyOf(System.currentTimeMillis())

        // 恢复当天已有记录（服务被系统重启 / 手动继续），恢复完成后才订阅定位。
        // 顺序不能颠倒：加载协程写 points/lastSaved/lastMovedAt，与主线程 onFix 的写入并发
        // （StateFlow 的 value = value + p 读改写非原子），先订阅会把刚收到的点覆盖掉、
        // 并把 lastSaved/lastMovedAt 回退成旧值（进而误判静止）。代价仅是首次定位晚几十毫秒。
        // 另：requestLocationUpdates 需要 Looper，此协程也必须在主线程。
        scope.launch(Dispatchers.Main) {
            if (!dayLoaded) {
                dayLoaded = true
                runCatching {
                    val pts = AppGraph.store.readDay(currentDay)
                    TrackerState.points.value = pts
                    lastSaved = pts.lastOrNull()
                    lastGoodGpsAt = pts.lastOrNull { it.prv == "gps" }?.t ?: System.currentTimeMillis()
                    lastMovedAt = pts.lastOrNull()?.t ?: System.currentTimeMillis()
                    if (pts.isNotEmpty()) updateNotification()
                }
            }
            startLocationUpdates(Prefs.fastIntervalMs)
        }

        // GPS 静默监测：室内/无信号时 GPS 可能长时间无 fix，定期检测并降频省电（恢复见 onFix）。
        // 注意必须在主线程：requestLocationUpdates 需要 Looper，Default 工作线程会崩（已踩坑）
        blackoutJob?.cancel()
        blackoutJob = scope.launch(Dispatchers.Main) {
            Log.d(TAG, "blackout checker started")
            while (true) {
                delay(30_000)
                checkGpsBlackout()
            }
        }

        // 落盘消费者：单协程从队列取点，严格按到达顺序串行写文件（不 cancel 上一笔，无丢点风险）
        saveJob?.cancel()
        if (saveQueue.isClosedForSend) saveQueue = Channel(Channel.UNLIMITED) // 上个周期已 close 排空，重建
        saveJob = scope.launch {
            for (p in saveQueue) {
                AppGraph.store.append(p)
            }
        }
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
        val now = System.currentTimeMillis()

        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        if (isGps) lastGpsFixAt = now

        // GPS 优先：GPS 仍在正常工作中时忽略网络 fix。
        // 网络定位（WiFi AP 缓存坐标）误差可达数百米且滞后，混存会把轨迹拉回成锯齿；
        // 仅当 GPS 连续失效超过 Prefs.gpsSilentAfterMs 后，网络 fix 才作为兜底保存。
        if (!isGps && !SavePolicy.isGpsStale(now, lastGpsFixAt, Prefs.gpsSilentAfterMs)) {
            updateNotification(throttle = true)
            return
        }

        if (!loc.hasAccuracy() || loc.accuracy <= 0f || loc.accuracy > Prefs.accFM) {
            // 定位收到了但不可用（或精度超过过滤门槛）：也刷新通知，让用户看出"正在收到定位（未被采纳）"
            updateNotification(throttle = true)
            return
        }
        // 精度可达标的 GPS fix 才计入"最近可用 GPS"（静默降频判定依据）
        if (isGps) lastGoodGpsAt = now
        // 记录最新可用 fix 坐标（WGS-84，供 UI"回到当前位置"使用）
        TrackerState.lastFixLoc.value = loc.latitude to loc.longitude

        // 跨天切换
        val day = dayKeyOf(now)
        if (day != currentDay) {
            currentDay = day
            TrackerState.points.value = emptyList()
            lastSaved = null
        }

        val p = TrackPoint(
            t = now,
            lat = loc.latitude,
            lon = loc.longitude,
            acc = loc.accuracy,
            spd = if (loc.hasSpeed()) loc.speed else 0f,
            prv = loc.provider ?: "",
        )
        val last = lastSaved
        if (last == null) {
            savePoint(p)
            return
        }

        val dt = now - last.t
        val dist = geoDistM(last.lat to last.lon, p.lat to p.lon)
        // 双源去重：4 秒内 3 米内视为同一位置的不同来源
        if (SavePolicy.isDuplicate(dt, dist)) return

        // GPS 瞬时毛刺（多路径）：位置位移推算速度 >> 多普勒报告速度 → 丢弃。
        // 被丢弃的点不保存也不更新 lastSaved/状态机，后续正常点拿原基准继续判定。
        if (isGps && SavePolicy.isMultipath(dist, dt, p.spd)) {
            Log.d(TAG, "multipath reject: dist=${dist.toInt()}m dt=${dt}ms spd=${p.spd}m/s")
            return
        }

        // 保存策略：移动超过阈值 或 到达在位心跳间隔
        if (SavePolicy.shouldSave(dist, dt, Prefs.minDistF, Prefs.keepAliveMs)) {
            savePoint(p)
        }

        // 二态状态机（行动中 / 静止），仅由 GPS 驱动：网络兜底坐标不可信，不参与升降频与移动判定
        if (!isGps) return

        // 退出静止 → 行动中：移动（位移 ≥10m）。
        // 一个条件同时覆盖两种静止来源（位移静止 / GPS 静默），
        // 且不会来回跳——静止用户即使 GPS fix 恢复也不升频（他没动）。
        if (dist >= Prefs.minDistF) {
            lastMovedAt = now
            if (staticMode) {
                staticMode = false
                TrackerState.isStatic.value = false
                startLocationUpdates(Prefs.fastIntervalMs)
                updateNotification()
            }
        } else if (!staticMode && SavePolicy.isStatic(dist, Prefs.minDistF, now, lastMovedAt, Prefs.staticAfterMs)) {
            // 进入静止（来源一：GPS fix 正常但位移小持续 staticAfter）
            staticMode = true
            TrackerState.isStatic.value = true
            startLocationUpdates(Prefs.staticIntervalMs)
            updateNotification()
        }
    }

    /**
     * 静止状态监测：距最后一次"可用 GPS fix"超过 Prefs.gpsSilentAfterMs
     * 且尚未进入静止时，把 GPS + 网络两个 provider 都降到 staticInterval，
     * 与位移静止共用同一个静止状态（退出同样只需移动）。
     * 注：必须在主线程调用（requestLocationUpdates 需要 Looper）。
     */
    private fun checkGpsBlackout() {
        val now = System.currentTimeMillis()
        Log.d(
            TAG,
            "blackout: staticMode=$staticMode rec=${TrackerState.isRecording.value} " +
                "stale=${SavePolicy.isGpsStale(now, lastGoodGpsAt, Prefs.gpsSilentAfterMs)} " +
                "ageSec=${(now - lastGoodGpsAt) / 1000}"
        )
        if (staticMode || !TrackerState.isRecording.value) return
        if (SavePolicy.isGpsStale(now, lastGoodGpsAt, Prefs.gpsSilentAfterMs)) {
            Log.d(TAG, "blackout -> static (GPS silent)")
            staticMode = true
            TrackerState.isStatic.value = true
            startLocationUpdates(Prefs.staticIntervalMs)
            updateNotification()
        }
    }

    private fun savePoint(p: TrackPoint) {
        lastSaved = p
        TrackerState.points.value = TrackerState.points.value + p
        // 串行落盘：入队即可（UNLIMITED 不阻塞），消费者协程按顺序逐一 flush
        saveQueue.trySend(p)
        updateNotification(throttle = true)
    }

    /** 停止前排空落盘队列：close 后消费者消费完剩余元素自动退出，join 等待写盘完成（毫秒级） */
    private fun drainSaveQueue() {
        saveQueue.close()
        runCatching { kotlinx.coroutines.runBlocking { saveJob?.join() } }
    }

    private fun stopTracking() {
        runCatching { locManager?.removeUpdates(listener) }
        TrackerState.isRecording.value = false
        TrackerState.isStatic.value = false
        dayLoaded = false
        drainSaveQueue()
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
            drainSaveQueue()
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
            else -> "记录中 · 行动中"
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