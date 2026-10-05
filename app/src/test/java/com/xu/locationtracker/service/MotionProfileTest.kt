package com.xu.locationtracker.service

import com.xu.locationtracker.service.MotionProfile.Level
import com.xu.locationtracker.service.MotionProfile.Quality
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionProfileTest {

    @Test
    fun `速度分档边界`() {
        assertEquals(Level.STATIC, MotionProfile.classify(0f))
        assertEquals(Level.STATIC, MotionProfile.classify(0.49f))
        assertEquals(Level.WALK, MotionProfile.classify(0.5f))
        assertEquals(Level.WALK, MotionProfile.classify(2.49f))
        assertEquals(Level.RIDE, MotionProfile.classify(2.5f))
        assertEquals(Level.RIDE, MotionProfile.classify(7.99f))
        assertEquals(Level.DRIVE, MotionProfile.classify(8f))
        assertEquals(Level.DRIVE, MotionProfile.classify(29.9f))
        assertEquals(Level.HIGH_SPEED, MotionProfile.classify(30f))
        assertEquals(Level.HIGH_SPEED, MotionProfile.classify(83f)) // 高铁 300km/h
    }

    @Test
    fun `三档预设采样间隔`() {
        assertEquals(15000L, MotionProfile.intervalMs(Level.WALK, Quality.ECO))
        assertEquals(10000L, MotionProfile.intervalMs(Level.WALK, Quality.STANDARD))
        assertEquals(5000L, MotionProfile.intervalMs(Level.WALK, Quality.FINE))

        assertEquals(6000L, MotionProfile.intervalMs(Level.RIDE, Quality.STANDARD))
        assertEquals(3000L, MotionProfile.intervalMs(Level.DRIVE, Quality.STANDARD))
        assertEquals(3000L, MotionProfile.intervalMs(Level.HIGH_SPEED, Quality.STANDARD))

        // STATIC 档沿用步行值（真正的静止降频由 staticInterval 覆盖）
        assertEquals(MotionProfile.intervalMs(Level.WALK, Quality.STANDARD), MotionProfile.intervalMs(Level.STATIC, Quality.STANDARD))
    }

    @Test
    fun `精度预设非法值回退标准档`() {
        assertEquals(Quality.STANDARD, MotionProfile.qualityOf(null))
        assertEquals(Quality.STANDARD, MotionProfile.qualityOf("nonsense"))
        assertEquals(Quality.STANDARD, MotionProfile.qualityOf("standard"))
        assertEquals(Quality.ECO, MotionProfile.qualityOf("ECO"))
        assertEquals(Quality.FINE, MotionProfile.qualityOf("Fine"))
    }

    @Test
    fun `速度缺失时用位置差分`() {
        val t = MotionProfile.Tracker()
        // 30m / 10s = 3 m/s → 骑行档（连续 3 次确认）
        repeat(3) { t.onFix(30f, 10_000, null) }
        assertEquals(Level.RIDE, t.level)
    }

    @Test
    fun `报告速度优先于位置差分`() {
        val t = MotionProfile.Tracker()
        // 差分为 0，但报告速度 15 m/s → 驾车档
        repeat(3) { t.onFix(0f, 10_000, 15f) }
        assertEquals(Level.DRIVE, t.level)
    }

    @Test
    fun `差分速度限幅`() {
        val t = MotionProfile.Tracker()
        // 异常差分（5000m/1s=5000m/s）应被限幅到 120 → 高速档，而非失控
        repeat(3) { t.onFix(5000f, 1_000, null) }
        assertEquals(Level.HIGH_SPEED, t.level)
    }

    @Test
    fun `稳定速度收敛到对应档位`() {
        val t = MotionProfile.Tracker()
        repeat(20) { t.onFix(null, 0, 1.2f) }
        assertEquals(Level.WALK, t.level)

        val t2 = MotionProfile.Tracker()
        repeat(20) { t2.onFix(null, 0, 5f) }
        assertEquals(Level.RIDE, t2.level)

        val t3 = MotionProfile.Tracker()
        repeat(20) { t3.onFix(null, 0, 20f) }
        assertEquals(Level.DRIVE, t3.level)

        val t4 = MotionProfile.Tracker()
        repeat(20) { t4.onFix(null, 0, 83f) }
        assertEquals(Level.HIGH_SPEED, t4.level)
    }

    @Test
    fun `迟滞防止边界抖动`() {
        val t = MotionProfile.Tracker()
        // 先稳定在步行 1 m/s
        repeat(20) { t.onFix(null, 0, 1f) }
        assertEquals(Level.WALK, t.level)

        // 2.6 m/s：已过骑行下界 2.5，但未超过步行上界×1.15=2.875 → 不升档
        repeat(20) { t.onFix(null, 0, 2.6f) }
        assertEquals(Level.WALK, t.level)

        // 3.0 m/s：超过 2.875 → 连续确认后升到骑行
        repeat(20) { t.onFix(null, 0, 3.0f) }
        assertEquals(Level.RIDE, t.level)

        // 降档同样有迟滞：2.6 m/s（高于骑行下界×0.85=2.125）→ 不回退
        repeat(20) { t.onFix(null, 0, 2.6f) }
        assertEquals(Level.RIDE, t.level)

        // 2.0 m/s：低于 2.125 → 降回步行
        repeat(20) { t.onFix(null, 0, 2.0f) }
        assertEquals(Level.WALK, t.level)
    }

    @Test
    fun `连续三次确认才切换档位`() {
        val t = MotionProfile.Tracker()
        // 从静止直接给 5 m/s：第一次 EMA=5 即达骑行档，但需连续 3 次确认
        t.onFix(null, 0, 5f)
        assertEquals(Level.STATIC, t.level)
        t.onFix(null, 0, 5f)
        assertEquals(Level.STATIC, t.level)
        t.onFix(null, 0, 5f)
        assertEquals(Level.RIDE, t.level)
    }

    @Test
    fun `reset 清空平滑与档位`() {
        val t = MotionProfile.Tracker()
        repeat(20) { t.onFix(null, 0, 83f) }
        assertEquals(Level.HIGH_SPEED, t.level)
        assertEquals(83f, t.speedMps, 0.01f)
        t.reset()
        assertEquals(Level.STATIC, t.level)
        assertEquals(0f, t.speedMps, 0.01f)
    }
}
