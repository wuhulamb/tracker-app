package com.xu.locationtracker.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavePolicyTest {

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
}