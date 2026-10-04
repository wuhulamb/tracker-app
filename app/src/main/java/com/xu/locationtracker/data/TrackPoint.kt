package com.xu.locationtracker.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轨迹点：时间 + 经纬度(WGS-84) + 水平精度(米) + 速度(米/秒)
 */
data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val spd: Float,
) {
    fun toJson(): String {
        // 保留足够精度：经纬度 6 位小数 ≈ 0.1m
        val sb = StringBuilder(96)
        sb.append("{\"t\":").append(t)
            .append(",\"lat\":").append(String.format(Locale.US, "%.6f", lat))
            .append(",\"lon\":").append(String.format(Locale.US, "%.6f", lon))
            .append(",\"acc\":").append(String.format(Locale.US, "%.1f", acc))
            .append(",\"spd\":").append(String.format(Locale.US, "%.2f", spd))
            .append('}')
        return sb.toString()
    }

    companion object {
        /** 每个点一行 JSON，形如 {"t":1700000000000,"lat":31.23,"lon":121.47,"acc":8.0,"spd":1.2} */
        fun fromJson(s: String): TrackPoint? = try {
            val o = JSONObject(s.trim())
            TrackPoint(
                t = o.getLong("t"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                acc = o.optDouble("acc", 0.0).toFloat(),
                spd = o.optDouble("spd", 0.0).toFloat(),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** 按设备本地时区取日期名：2025-01-01 */
fun dayKeyOf(t: Long): String = DAY_FMT.format(Date(t))

private val DAY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)