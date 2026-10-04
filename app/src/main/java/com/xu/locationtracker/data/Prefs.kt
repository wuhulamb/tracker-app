package com.xu.locationtracker.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置。纯 SharedPreferences，无数据库。
 */
object Prefs {
    private lateinit var sp: SharedPreferences
    const val MODE_AUTO = "auto"
    const val MODE_MANUAL = "manual"

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    // ---------- 记录模式 ----------

    var mode: String
        get() = sp.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO
        set(v) = sp.edit().putString(KEY_MODE, v).apply()

    /** 手动模式下是否正在记录 */
    var manualRecording: Boolean
        get() = sp.getBoolean(KEY_MANUAL_REC, false)
        set(v) = sp.edit().putBoolean(KEY_MANUAL_REC, v).apply()

    /** 自动模式下"结束今日记录"的日期，当天不再自动重启 */
    var autoStoppedForDay: String
        get() = sp.getString(KEY_AUTO_STOP_DAY, "") ?: ""
        set(v) = sp.edit().putString(KEY_AUTO_STOP_DAY, v).apply()

    // ---------- 记录参数（可在设置页修改） ----------

    /** 精度过滤：accuracy 超过该值(米)的点丢弃 */
    var filterAccuracyM: Int
        get() = sp.getInt(KEY_ACC, 50)
        set(v) = sp.edit().putInt(KEY_ACC, v).apply()

    /** 常规定位间隔（秒） */
    var fastIntervalSec: Int
        get() = sp.getInt(KEY_FAST_INT, 10)
        set(v) = sp.edit().putInt(KEY_FAST_INT, v).apply()

    /** 保存触发：与上一个点位移达到该值(米)就记录 */
    var minDistM: Int
        get() = sp.getInt(KEY_MIN_DIST, 10)
        set(v) = sp.edit().putInt(KEY_MIN_DIST, v).apply()

    /** 静止判定：连续该分钟数位移都小于 minDist 则降频 */
    var staticAfterMin: Int
        get() = sp.getInt(KEY_STATIC_AFTER, 5)
        set(v) = sp.edit().putInt(KEY_STATIC_AFTER, v).apply()

    /** 静止降频后的定位间隔（秒） */
    var staticIntervalSec: Int
        get() = sp.getInt(KEY_STATIC_INT, 60)
        set(v) = sp.edit().putInt(KEY_STATIC_INT, v).apply()

    /** 静止期间"在位心跳"：每隔该分钟数记一个点（表明仍在原地），0=不记 */
    var keepAliveMin: Int
        get() = sp.getInt(KEY_KEEPALIVE, 5)
        set(v) = sp.edit().putInt(KEY_KEEPALIVE, v).apply()

    // ---------- 派生便捷属性 ----------

    val fastIntervalMs: Long get() = fastIntervalSec * 1000L
    val staticIntervalMs: Long get() = staticIntervalSec * 1000L
    val staticAfterMs: Long get() = staticAfterMin * 60_000L
    val keepAliveMs: Long get() = keepAliveMin * 60_000L
    val minDistF: Float get() = minDistM.toFloat()
    val accFM: Float get() = filterAccuracyM.toFloat()

    private const val KEY_MODE = "mode"
    private const val KEY_MANUAL_REC = "manual_recording"
    private const val KEY_AUTO_STOP_DAY = "auto_stopped_for_day"
    private const val KEY_ACC = "filter_accuracy"
    private const val KEY_FAST_INT = "fast_interval"
    private const val KEY_MIN_DIST = "min_dist"
    private const val KEY_STATIC_AFTER = "static_after"
    private const val KEY_STATIC_INT = "static_interval"
    private const val KEY_KEEPALIVE = "keepalive"
}