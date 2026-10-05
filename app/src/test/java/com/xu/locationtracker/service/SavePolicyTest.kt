package com.xu.locationtracker.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavePolicyTest {

    private val FALLBACK = 120_000L // 120s，显式测试阈值

    @Test
    fun `GPS 时效判定_未失效时网络fix被忽略`() {
        val now = 1_000_000L
        // GPS 刚刚更新过
        assertFalse(SavePolicy.isGpsStale(now, now - 60_000, FALLBACK))
        // 差 1ms 到阈值：仍未失效
        assertFalse(SavePolicy.isGpsStale(now, now - FALLBACK + 1, FALLBACK))
    }

    @Test
    fun `GPS 时效判定_超过阈值后网络兜底可用`() {
        val now = 1_000_000L
        // 恰好等于阈值：视为失效，网络兜底可用
        assertTrue(SavePolicy.isGpsStale(now, now - FALLBACK, FALLBACK))
        // 超过阈值
        assertTrue(SavePolicy.isGpsStale(now, now - FALLBACK - 100, FALLBACK))
        // 从未收到 GPS：直接兜底
        assertTrue(SavePolicy.isGpsStale(now, 0, FALLBACK))
    }

    @Test
    fun `双源去重_4秒内3米内`() {
        assertTrue(SavePolicy.isDuplicate(3_000, 2f))
        assertFalse(SavePolicy.isDuplicate(5_000, 2f)) // 时间超了不去重
        assertFalse(SavePolicy.isDuplicate(3_000, 5f)) // 距离超了不去重
    }

    @Test
    fun `移动超过阈值保存`() {
        assertTrue(SavePolicy.shouldSave(12f, 9_000, 10f, 300_000))
        assertFalse(SavePolicy.shouldSave(3f, 9_000, 10f, 300_000))
        // 恰好等于阈值也保存
        assertTrue(SavePolicy.shouldSave(10f, 9_000, 10f, 300_000))
    }

    @Test
    fun `心跳按时间保存`() {
        assertTrue(SavePolicy.shouldSave(3f, 300_000, 10f, 300_000))
        assertFalse(SavePolicy.shouldSave(3f, 299_000, 10f, 300_000))
        assertFalse(SavePolicy.shouldSave(3f, 500_000, 10f, 0)) // 心跳关闭（0）
    }

    @Test
    fun `静止判定`() {
        assertTrue(SavePolicy.isStatic(3f, 10f, 400_000, 100_000, 300_000))
        assertFalse(SavePolicy.isStatic(3f, 10f, 399_000, 100_000, 300_000)) // 差 1 秒
        assertFalse(SavePolicy.isStatic(12f, 10f, 400_000, 100_000, 300_000)) // 在移动
        assertTrue(SavePolicy.isStatic(3f, 10f, 400_000, 100_000, 300_000))   // 恰好到点也静止
    }

    @Test
    fun `GPS多路径毛刺_位移速度比异常时丢弃`() {
        // 真实场景：08:54:45→08:54:47 位移 153m/2s ≈ 76.5m/s，spd=5.11 → 比值 15
        assertTrue(SavePolicy.isMultipath(153f, 2_000L, 5.11f))
        // 正常移动：位移速度 ≈ 报告速度
        assertFalse(SavePolicy.isMultipath(30f, 10_000L, 3f))   // 3/3 = 1 倍
        assertFalse(SavePolicy.isMultipath(60f, 10_000L, 5f))   // 6/5 = 1.2 倍
        // 临界：恰好 4 倍不丢，>4 倍才丢
        assertFalse(SavePolicy.isMultipath(200f, 10_000L, 5f))  // 20/5 = 4.0
        assertTrue(SavePolicy.isMultipath(210f, 10_000L, 5f))   // 21/5 = 4.2
        // 高速车辆不误伤：25m/s ≈ 90km/h
        assertFalse(SavePolicy.isMultipath(250f, 10_000L, 25f))
    }

    @Test
    fun `GPS多路径毛刺_无速度时用绝对位移速度兜底`() {
        // spd<=0.5（静止/慢速上下文）：位移速度 >12m/s 视为毛刺
        assertTrue(SavePolicy.isMultipath(130f, 5_000L, 0.0f))    // 26 m/s
        assertFalse(SavePolicy.isMultipath(50f, 5_000L, 0.3f))    // 10 m/s，保留
        assertFalse(SavePolicy.isMultipath(100f, 10_000L, 0.4f))  // 10 m/s，保留
    }

    @Test
    fun `GPS多路径毛刺_长间隔不判避免弦长误杀`() {
        // 跳过毛刺后的正常点相对旧基准是长弦：5min 心跳 / 40s 间隔都不判
        assertFalse(SavePolicy.isMultipath(215f, 300_000L, 4.94f)) // 心跳间隔
        assertFalse(SavePolicy.isMultipath(153f, 40_000L, 5.11f))  // 40s 长间隔
        // 过短间隔（多源同帧）也不判
        assertFalse(SavePolicy.isMultipath(100f, 200L, 5f))
    }

    @Test
    fun `无报告速度时按档位速度放宽毛刺上限`() {
        // 高铁 83m/s、无报告速度、档位速度 83 → 上限 max(12, 4×83)=332 → 不判（修复高铁误杀）
        assertFalse(SavePolicy.isMultipath(830f, 10_000L, 0f, expectedSpeedMps = 83f))
        // 同一位移但档位速度仍为 0（恢复首点、基线未建立）→ 阈值 12 → 判为毛刺
        assertTrue(SavePolicy.isMultipath(830f, 10_000L, 0f, expectedSpeedMps = 0f))
        // 步行档位基线：12m/s 下限仍生效
        assertTrue(SavePolicy.isMultipath(130f, 10_000L, 0f, expectedSpeedMps = 1.4f)) // 13 > max(12, 5.6)
        assertFalse(SavePolicy.isMultipath(50f, 5_000L, 0f, expectedSpeedMps = 1.4f))  // 10 ≤ 12
        // 驾车档位基线：放宽但不失控
        assertFalse(SavePolicy.isMultipath(250f, 10_000L, 0f, expectedSpeedMps = 20f))  // 25 ≤ max(12, 80)
        assertTrue(SavePolicy.isMultipath(850f, 10_000L, 0f, expectedSpeedMps = 20f))   // 85 > 80
    }
}