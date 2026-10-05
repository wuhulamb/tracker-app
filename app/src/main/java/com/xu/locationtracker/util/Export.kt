package com.xu.locationtracker.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.xu.locationtracker.data.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出为 GPX / CSV 到系统"下载/行程轨迹"目录（Android 10+ 无需任何权限，
 * 通过 USB/MTP 或文件管理器可直接取出）。
 */
object Exporter {

    suspend fun exportGpx(ctx: Context): String? = withContext(Dispatchers.IO) {
        val days = AppGraph.store.listDays()
        if (days.isEmpty()) return@withContext null
        val sb = StringBuilder(1 shl 16)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<gpx version=\"1.1\" creator=\"LocationTracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (day in days) {
            val pts = AppGraph.store.readDay(day)
            if (pts.isEmpty()) continue
            sb.append("  <trk><name>").append(day).append("</name><trkseg>\n")
            for (p in pts) {
                sb.append("    <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">")
                    .append("<ele>0</ele><time>").append(iso8601(p.t)).append("</time>")
                    .append("</trkpt>\n")
            }
            sb.append("  </trkseg></trk>\n")
        }
        sb.append("</gpx>\n")
        saveToDownloads(ctx, "行程轨迹_全部.gpx", "application/gpx+xml", sb.toString())
    }

    suspend fun exportCsv(ctx: Context): String? = withContext(Dispatchers.IO) {
        val days = AppGraph.store.listDays()
        if (days.isEmpty()) return@withContext null
        val sb = StringBuilder(1 shl 16)
        sb.append("日期,时间(本地),经度,纬度,精度(m),速度(m/s)\n")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        for (day in days) {
            val pts = AppGraph.store.readDay(day)
            for (p in pts) {
                sb.append(day).append(',')
                    .append(fmt.format(Date(p.t))).append(',')
                    .append(p.lon).append(',')
                    .append(p.lat).append(',')
                    .append(p.acc).append(',')
                    .append(p.spd)
                    .append('\n')
            }
        }
        saveToDownloads(ctx, "行程轨迹_全部.csv", "text/csv", "\uFEFF" + sb.toString())
    }

    /** 返回保存后的 Uri 字符串，失败返回 null */
    private fun saveToDownloads(ctx: Context, name: String, mime: String, content: String): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 先删除同名旧文件，避免产生“xxx (1)”重复副本
            runCatching {
                ctx.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/行程轨迹%")
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/行程轨迹")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                ctx.contentResolver.openOutputStream(uri, "w")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                ctx.contentResolver.delete(uri, null, null)
                return null
            }
            uri.toString()
        } else {
            // Android 9 及以下：写入应用外部目录并提示 adb 取出
            val dir = ctx.getExternalFilesDir(null)
            val f = File(dir, name)
            f.writeText(content, Charsets.UTF_8)
            f.absolutePath
        }
    }

    private fun iso8601(t: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date(t))
    }
}