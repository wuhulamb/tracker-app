package com.xu.locationtracker.service

/**
 * 定位点保存 / 静止降频判定（纯逻辑，无 Android 依赖，可单测）。
 * 与 TrackingService.onFix 配合使用，行为与其内联实现保持一致。
 */
object SavePolicy {

    /** GPS 是否已失效（超过 fallbackAfterMs 无 GPS fix）。lastGpsFixMs=0 表示从未收到 GPS */
    fun isGpsStale(nowMs: Long, lastGpsFixMs: Long, fallbackAfterMs: Long): Boolean =
        nowMs - lastGpsFixMs >= fallbackAfterMs

    /** 双源去重：4 秒内 3 米内视为同一位置的不同来源 */
    fun isDuplicate(dtMs: Long, distM: Float): Boolean = dtMs < 4_000 && distM < 3f

    /** 是否保存该点：移动超过阈值，或到达在位心跳间隔（keepAliveMs = 0 表示关闭心跳） */
    fun shouldSave(distM: Float, dtMs: Long, minDistM: Float, keepAliveMs: Long): Boolean =
        distM >= minDistM || (keepAliveMs > 0 && dtMs >= keepAliveMs)

    /** 是否应进入静止降频：位移小于阈值，且距上次移动已超过 staticAfterMs */
    fun isStatic(distM: Float, minDistM: Float, nowMs: Long, lastMovedAtMs: Long, staticAfterMs: Long): Boolean =
        distM < minDistM && (nowMs - lastMovedAtMs) >= staticAfterMs

    /**
     * GPS 瞬时毛刺（多路径）判定：位置位移推算出的平均速度远大于 GPS 多普勒报告速度时，
     * 该位置大概率被楼群/反射信号污染（位置可瞬间偏 100~200m，而多普勒速度几乎不受影响）。
     *
     * 仅由调用方对 GPS fix 启用；被丢弃的点不保存、也不更新 lastSaved/状态机。
     * 只判断短间隔（0.5s~15s）——长间隔（含 5min 心跳）不做判定，避免"跳过毛刺后的弦长"误杀正常点。
     *
     * 无报告速度时（冷启动/恢复首 fix/部分芯片）不再用固定 12m/s：改用**当前运动档位的
     * 平滑速度** `expectedSpeedMps` 推期望上限（下限仍 12m/s），使高速（高铁 83m/s）不被误杀。
     */
    fun isMultipath(
        distM: Float,
        dtMs: Long,
        spd: Float,
        expectedSpeedMps: Float = 0f,
        minDtMs: Long = 500,
        maxDtMs: Long = 15_000,
    ): Boolean {
        if (dtMs < minDtMs || dtMs > maxDtMs) return false
        val vEst = distM / (dtMs / 1000f) // 由位移推算的平均速度 m/s
        return if (spd > 0.5f) {
            vEst / spd > 4f // 位置速度比报告速度高 4 倍以上
        } else {
            vEst > maxOf(12f, 4f * expectedSpeedMps) // 无可靠速度：按档位速度放宽上限（下限 12m/s）
        }
    }
}