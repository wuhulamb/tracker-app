package com.xu.locationtracker.ui

import android.content.Context
import java.io.File

/**
 * 地图样式管理：
 *  - 优先使用 OSM 离线包（shanghai-osm.mbtiles，WGS-84 / "GPS 坐标"，标注即 OSM 原生中文）
 *  - 其次使用高德离线包（shanghai.mbtiles，GCJ-02，显示时需纠偏）
 *  - 都没有时回退在线高德瓦片（GCJ-02，开发/兜底用）
 * 实际样式 JSON 动态写入内部存储，MapLibre 以 file:// 加载。
 * gcj 标志告知渲染层底图坐标系：true = GCJ-02 需做 wgsToGcj，false = WGS-84 直接显示。
 */
object MapStyles {

    const val MAPS_SUBDIR = "maps"
    const val MAPS_FILE_WGS = "shanghai-osm.mbtiles"        // OSM 栅格（WGS-84）
    const val MAPS_FILE_GCJ = "shanghai.mbtiles"             // 高德栅格（GCJ-02）
    const val MAPS_FILE_VECTOR = "shanghai-osm-vector.mbtiles" // OSM 矢量（WGS-84，MVT）

    data class StyleSpec(val url: String, val gcj: Boolean)

    fun mapsFile(ctx: Context, name: String): File =
        File(File(ctx.getExternalFilesDir(null), MAPS_SUBDIR), name)

    /** 返回可直接 setStyle 的样式 URL + 底图坐标系标志；同时把样式写入 filesDir/style-active.json */
    fun styleSpec(ctx: Context): StyleSpec {
        // 1) OSM 矢量离线包（WGS-84）优先
        val vec = mapsFile(ctx, MAPS_FILE_VECTOR)
        if (vec.exists() && vec.length() > 0) {
            return StyleSpec(writeVectorStyle(ctx, vec), gcj = false)
        }
        // 2) OSM 栅格离线包（WGS-84）
        val wgs = mapsFile(ctx, MAPS_FILE_WGS)
        if (wgs.exists() && wgs.length() > 0) {
            return StyleSpec(
                writeStyle(ctx, offlineStyle("mbtiles://" + wgs.absolutePath, "上海离线底图 · OSM (WGS-84 GPS)")),
                gcj = false,
            )
        }
        // 3) 高德栅格离线包（GCJ-02）
        val gcj = mapsFile(ctx, MAPS_FILE_GCJ)
        if (gcj.exists() && gcj.length() > 0) {
            return StyleSpec(
                writeStyle(ctx, offlineStyle("mbtiles://" + gcj.absolutePath, "上海离线底图 · 高德 (GCJ-02)")),
                gcj = true,
            )
        }
        // 4) 在线高德（GCJ-02，兜底）
        return StyleSpec(writeStyle(ctx, ONLINE_STYLE), gcj = true)
    }

    /** 矢量离线样式：读内置模板，把 mbtiles 路径占位符替换为本地绝对路径 */
    private fun writeVectorStyle(ctx: Context, vec: File): String {
        val template = runCatching {
            ctx.assets.open("styles/vector_offline.json").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val json = template.replace("MBTILES_PLACEHOLDER", "mbtiles://" + vec.absolutePath)
        return writeStyle(ctx, json)
    }

    private fun writeStyle(ctx: Context, json: String): String {
        val f = File(ctx.filesDir, "style-active.json")
        runCatching { f.writeText(json) }
        return "file://" + f.absolutePath
    }

    private fun offlineStyle(mbtilesUrl: String, attribution: String): String = """
{
  "version": 8,
  "name": "shanghai-offline",
  "sources": {
    "local": {
      "type": "raster",
      "tiles": ["$mbtilesUrl"],
      "tileSize": 256,
      "minzoom": 10,
      "maxzoom": 18,
      "attribution": "$attribution"
    }
  },
  "layers": [
    { "id": "bg", "type": "background", "paint": { "background-color": "#e8ece4" } },
    { "id": "local", "type": "raster", "source": "local" }
  ]
}
"""

    const val ONLINE_STYLE = """
{
  "version": 8,
  "name": "gaode-online",
  "sources": {
    "gaode": {
      "type": "raster",
      "tiles": ["https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}"],
      "tileSize": 256,
      "minzoom": 3,
      "maxzoom": 19,
      "attribution": "© 高德地图"
    }
  },
  "layers": [
    { "id": "bg", "type": "background", "paint": { "background-color": "#eef1f3" } },
    { "id": "gaode", "type": "raster", "source": "gaode" }
  ]
}
"""
}