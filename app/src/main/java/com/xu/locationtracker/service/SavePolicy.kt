package com.xu.locationtracker.service

/**
 * 定位点保存 / 静止降频判定（纯逻辑，无 Android 依赖，可单测）。
 * 与 TrackingService.onFix 配合使用，行为与其内联实现保持一致。
 */
object SavePolicy {

    /** 双源去重：4 秒内 3 米内视为同一位置的不同来源 */
    fun isDuplicate(dtMs: Long, distM: Float): Boolean = dtMs < 4_000 && distM < 3f

    /** 是否保存该点：移动超过阈值，或到达在位心跳间隔（keepAliveMs = 0 表示关闭心跳） */
    fun shouldSave(distM: Float, dtMs: Long, minDistM: Float, keepAliveMs: Long): Boolean =
        distM >= minDistM || (keepAliveMs > 0 && dtMs >= keepAliveMs)

    /** 是否应进入静止降频：位移小于阈值，且距上次移动已超过 staticAfterMs */
    fun isStatic(distM: Float, minDistM: Float, nowMs: Long, lastMovedAtMs: Long, staticAfterMs: Long): Boolean =
        distM < minDistM && (nowMs - lastMovedAtMs) >= staticAfterMs
}