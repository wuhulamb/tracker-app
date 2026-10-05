package com.xu.locationtracker.ui

import android.content.Context
import java.io.File

/**
 * 地图样式管理：唯一底图为「在线 OSM 矢量地图（OpenFreeMap）」。
 *  - 数据源 tiles.openfreemap.org，shortbread schema，WGS-84（无需坐标纠偏）
 *  - 全球 z0-15+，数据完整（浦东等郊区道路密度约为旧离线矢量包的 5 倍）
 * 官方 Bright 样式内置为 assets/styles/openfreemap_online.json。
 * 实际样式 JSON 动态写入内部存储，MapLibre 以 file:// 加载。
 * gcj 标志告知渲染层底图坐标系：本底图为 false（WGS-84 直接显示）。
 */
object MapStyles {

    data class StyleSpec(val url: String, val gcj: Boolean)

    /** 返回可直接 setStyle 的样式 URL；同时把样式写入 filesDir/style-active.json */
    fun styleSpec(ctx: Context): StyleSpec {
        val online = runCatching {
            ctx.assets.open("styles/openfreemap_online.json").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        if (online.isNotBlank()) {
            return StyleSpec(writeStyle(ctx, online), gcj = false)
        }
        // 仅当内置样式资产缺失时：纯色背景兜底（不引用任何外部底图）
        return StyleSpec(writeStyle(ctx, BLANK_STYLE), gcj = false)
    }

    private fun writeStyle(ctx: Context, json: String): String {
        val f = File(ctx.filesDir, "style-active.json")
        runCatching { f.writeText(json) }
        return "file://" + f.absolutePath
    }

    private const val BLANK_STYLE = """
{
  "version": 8,
  "sources": {},
  "layers": [
    { "id": "bg", "type": "background", "paint": { "background-color": "#e8ece4" } }
  ]
}
"""
}