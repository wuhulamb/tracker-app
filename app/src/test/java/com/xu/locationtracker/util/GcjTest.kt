package com.xu.locationtracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GcjTest {

    @Test
    fun `境外坐标不偏移`() {
        // 纽约
        val (lat, lon) = Gcj.wgsToGcj(40.7128, -74.0060)
        assertEquals(40.7128, lat, 0.0)
        assertEquals(-74.0060, lon, 0.0)
    }

    @Test
    fun `上海坐标发生偏移且幅度合理`() {
        val (lat, lon) = Gcj.wgsToGcj(31.23, 121.47)
        assertTrue(java.lang.Double.isFinite(lat))
        assertTrue(java.lang.Double.isFinite(lon))
        // 国测局偏移通常几十到几百米：0.001° 约 100m
        assertTrue(abs(lat - 31.23) in 1e-6..0.01)
        assertTrue(abs(lon - 121.47) in 1e-6..0.01)
        // 关键：确实发生了变化（否则 GPS 叠加底图会偏出数百米）
        assertTrue(abs(lat - 31.23) > 1e-6)
    }

    @Test
    fun `偏移函数平滑连续`() {
        // 微小纬度差 → 偏移差也微小（无跳变）
        val a = Gcj.wgsToGcj(31.230000, 121.470000)
        val b = Gcj.wgsToGcj(31.230001, 121.470000)
        assertTrue(abs(b.first - a.first) < 1e-5)
        val c = Gcj.wgsToGcj(31.230000, 121.470001)
        assertTrue(abs(c.second - a.second) < 1e-5)
    }

    @Test
    fun `fmtDur 格式`() {
        assertEquals("0:00:00", fmtDur(0))
        assertEquals("0:00:00", fmtDur(-1))
        assertEquals("1:01:02", fmtDur(3662_000))
        assertEquals("0:00:59", fmtDur(59_000))
        assertEquals("10:00:00", fmtDur(36_000_000))
    }

    @Test
    fun `lastFixAgeText 分级`() {
        assertEquals("7 秒前", lastFixAgeText(7))
        assertEquals("59 秒前", lastFixAgeText(59))
        assertEquals("1 分钟前", lastFixAgeText(60))
        assertEquals("3 分钟前", lastFixAgeText(180))
        assertEquals("1 小时前", lastFixAgeText(3600))
        assertEquals("2 小时前", lastFixAgeText(7200))
    }
}