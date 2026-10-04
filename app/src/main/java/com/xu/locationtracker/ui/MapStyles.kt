package com.xu.locationtracker.ui

import android.content.Context
import java.io.File

/**
 * 地图样式管理：
 *  - 若存在离线瓦片文件（PC 端脚本生成后 adb push 进来）→ 使用 mbtiles:// 离线样式
 *  - 否则回退到在线高德瓦片样式（开发/兜底用）
 * 实际样式 JSON 动态写入内部存储，MapLibre 以 file:// 加载。
 */
object MapStyles {

    const val MAPS_SUBDIR = "maps"
    const val MAPS_FILE = "shanghai.mbtiles"

    fun mapsFile(ctx: Context): File =
        File(File(ctx.getExternalFilesDir(null), MAPS_SUBDIR), MAPS_FILE)

    fun isOffline(ctx: Context): Boolean = mapsFile(ctx).exists() && mapsFile(ctx).length() > 0

    fun sizeMb(ctx: Context): Long = mapsFile(ctx).length() / 1_000_000

    /** 返回可直接 setStyle 的样式 URL；同时把样式写入 filesDir/style-active.json */
    fun styleUrl(ctx: Context): String {
        val mbtiles = mapsFile(ctx)
        val json = if (mbtiles.exists() && mbtiles.length() > 0) {
            offlineStyle("mbtiles://" + mbtiles.absolutePath)
        } else {
            ONLINE_STYLE
        }
        val f = File(ctx.filesDir, "style-active.json")
        runCatching { f.writeText(json) }
        return "file://" + f.absolutePath
    }

    private fun offlineStyle(mbtilesUrl: String): String = """
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
      "attribution": "上海离线底图"
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