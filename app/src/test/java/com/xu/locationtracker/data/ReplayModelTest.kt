package com.xu.locationtracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayModelTest {

    private fun pts(lats: DoubleArray): List<Pair<Double, Double>> = lats.map { it to 121.45 }

    private fun times(sec: LongArray): LongArray = LongArray(sec.size) { sec[it] * 1000 }

    @Test
    fun `空模型安全返回`() {
        val m = ReplayModel.from(emptyList(), LongArray(0))
        assertEquals(0.0, m.totalSec, 1e-6)
        val (idx, frac, t) = m.locate(0.0)
        assertEquals(0, idx)
        assertEquals(0.0, frac, 1e-6)
        assertEquals(0L, t)
    }

    @Test
    fun `单点模型始终落在该点`() {
        val m = ReplayModel.from(pts(doubleArrayOf(31.0)), times(longArrayOf(100)))
        assertEquals(0.0, m.totalSec, 1e-6)
        val (idx, frac, t) = m.locate(0.0)
        assertEquals(0, idx)
        assertEquals(0.0, frac, 1e-6)
        assertEquals(100_000L, t)
    }

    @Test
    fun `静止间隔被压缩到最大值`() {
        // 间隔 120s → 压缩为 30s；间隔 5s 原样
        val m = ReplayModel.from(pts(doubleArrayOf(31.0, 31.001, 31.002)), times(longArrayOf(0, 120, 125)))
        assertEquals(30.0 + 5.0, m.totalSec, 1e-6)
        // 自定义压缩上限
        val m2 = ReplayModel.from(pts(doubleArrayOf(31.0, 31.001)), times(longArrayOf(0, 120)), maxGapSec = 10.0)
        assertEquals(10.0, m2.totalSec, 1e-6)
    }

    @Test
    fun `locate 段内插值与真实时间`() {
        val m = ReplayModel.from(pts(doubleArrayOf(31.0, 31.01, 31.02)), times(longArrayOf(0, 10, 20)))
        // e=5s → 段 0，frac 0.5，真实时间 = 5s
        val (idx, frac, t) = m.locate(5.0)
        assertEquals(0, idx)
        assertEquals(0.5, frac, 1e-6)
        assertEquals(5_000L, t)
        // 越界 e → 落在最后一段开头，frac 顶到 1.0
        val (idx2, frac2, t2) = m.locate(999.0)
        assertEquals(1, idx2)
        assertEquals(1.0, frac2, 1e-6)
        assertEquals(20_000L, t2)
    }

    @Test
    fun `posAt 线性插值与越界`() {
        val m = ReplayModel.from(pts(doubleArrayOf(31.0, 31.01, 31.02)), times(longArrayOf(0, 10, 20)))
        assertEquals(31.015, m.posAt(1, 0.5).first, 1e-9)
        assertEquals(31.02, m.posAt(5, 0.5).first, 1e-9) // 越界返回最后一点
    }

    @Test
    fun `长缺口内的插值_线尾跟随场景`() {
        // 模拟 41→42 那种 200s 长缺口：距离被压缩到 30s 播放时长
        val m = ReplayModel.from(pts(doubleArrayOf(31.0, 31.01)), times(longArrayOf(0, 200)))
        assertEquals(30.0, m.totalSec, 1e-6)
        val (_, frac, t) = m.locate(21.0) // 21/30 = 0.7
        assertEquals(0.7, frac, 1e-6)
        assertEquals(140_000L, t)         // 真实时间 = 0.7 * 200s
    }
}