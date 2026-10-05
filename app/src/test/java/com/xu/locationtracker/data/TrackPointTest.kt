package com.xu.locationtracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPointTest {

    @Test
    fun `toJson 包含定位来源字段`() {
        val p = TrackPoint(1_700_000_000_000L, 31.03389, 121.445608, 8.0f, 1.2f, "gps")
        val s = p.toJson()
        assertTrue("\"prv\":\"gps\"" in s)
        assertTrue(s.startsWith("{\"t\":") && s.endsWith("}"))
    }

    @Test
    fun `toJson 与 fromJson 往返一致`() {
        val p = TrackPoint(1_700_000_000_000L, 31.03389, 121.445608, 8.0f, 1.2f, "network")
        val back = TrackPoint.fromJson(p.toJson())!!
        assertEquals(p, back)
    }

    @Test
    fun `fromJson 缺 prv 字段视为非法行返回 null`() {
        // 无 prv 字段的旧格式行：不再兼容，解析失败
        val old = """{"t":1700000000000,"lat":31.03389,"lon":121.445608,"acc":8.0,"spd":1.2}"""
        assertNull(TrackPoint.fromJson(old))
    }

    @Test
    fun `fromJson 非法行返回 null`() {
        assertNull(TrackPoint.fromJson("not-json"))
        assertNull(TrackPoint.fromJson("{}"))
        assertNull(TrackPoint.fromJson("""{"t":1,"lat":31.0,"lon":121.0,"acc":8.0,"spd":1.2,"prv":123}""")) // prv 非字符串
    }
}