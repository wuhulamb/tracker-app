package com.xu.locationtracker.data

/**
 * 回放模型：把真实时间轴压缩（静止间隔最多记 maxGapSec 秒），
 * cum[i] = 播放到点 i 所需的"有效秒数"。
 * 纯 Kotlin 实现，无 Android 依赖，可单测。
 */
class ReplayModel(
    val gcj: List<Pair<Double, Double>>,   // (lat, lon)，GCJ-02
    val ts: LongArray,                     // 对应点的时间戳（ms）
    val cum: DoubleArray,                  // 压缩后的累计播放时长（秒）
) {
    val totalSec: Double get() = if (cum.isEmpty()) 0.0 else cum[cum.size - 1]

    /** 有效秒 e -> (段索引, 段内比例, 模拟的真实时间戳ms) */
    fun locate(e: Double): Triple<Int, Double, Long> {
        val n = gcj.size
        if (n == 0) return Triple(0, 0.0, 0L)
        if (n == 1) return Triple(0, 0.0, ts[0])
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= e) lo = mid else hi = mid - 1
        }
        val idx = lo.coerceAtMost(n - 2)
        val segLen = (cum[idx + 1] - cum[idx]).coerceAtLeast(1e-6)
        val frac = ((e - cum[idx]) / segLen).coerceIn(0.0, 1.0)
        val tNow = ts[idx] + ((ts[idx + 1] - ts[idx]) * frac).toLong()
        return Triple(idx, frac, tNow)
    }

    /** 位置插值（GCJ lat,lon） */
    fun posAt(idx: Int, frac: Double): Pair<Double, Double> {
        if (idx >= gcj.size - 1) return gcj.last()
        val a = gcj[idx]
        val b = gcj[idx + 1]
        return a.first + (b.first - a.first) * frac to a.second + (b.second - a.second) * frac
    }

    companion object {
        /**
         * 构造回放模型。
         * @param maxGapSec 相邻点间隔超过它的部分压缩掉（静止间隔在回放里最多记这么多秒）
         */
        fun from(gcjPts: List<Pair<Double, Double>>, times: LongArray, maxGapSec: Double = 30.0): ReplayModel {
            val n = gcjPts.size
            val cum = DoubleArray(n)
            for (i in 1 until n) {
                val gap = (times[i] - times[i - 1]) / 1000.0
                cum[i] = cum[i - 1] + gap.coerceAtMost(maxGapSec)
            }
            return ReplayModel(gcjPts, times, cum)
        }
    }
}