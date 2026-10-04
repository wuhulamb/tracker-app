package com.xu.locationtracker.util

import com.xu.locationtracker.data.TrackPoint

/** 两点球面距离（米），供地图线尾跟随与保存判定共用 */
fun geoDistM(a: Pair<Double, Double>, b: Pair<Double, Double>): Float {
    val r = FloatArray(1)
    android.location.Location.distanceBetween(a.first, a.second, b.first, b.second, r)
    return r[0]
}

/** 轨迹统计：(总距离m, 总时长ms, 点数)。不足两个点时不计算距离。 */
fun trackStats(pts: List<TrackPoint>): Triple<Double, Long, Int> {
    if (pts.size < 2) return Triple(0.0, 0L, pts.size)
    var d = 0.0
    for (i in 1 until pts.size) {
        d += geoDistM(pts[i - 1].lat to pts[i - 1].lon, pts[i].lat to pts[i].lon).toDouble()
    }
    return Triple(d, pts.last().t - pts.first().t, pts.size)
}