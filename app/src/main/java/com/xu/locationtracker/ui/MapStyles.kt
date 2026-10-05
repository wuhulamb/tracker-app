package com.xu.locationtracker.ui

import android.content.Context
import com.xu.locationtracker.data.Prefs
import java.io.File

/**
 * 地图样式管理：底图为在线 OSM 矢量地图（OpenFreeMap），样式可选官方 4 种风格。
 *  - 数据源 tiles.openfreemap.org，shortbread schema，WGS-84（无需坐标纠偏）
 *  - 各样式 JSON 内置为 assets/styles/ofm_<style>.json（已移除 ne2 影像阴影层）
 * 实际样式 JSON 动态写入内部存储，MapLibre 以 file:// 加载。
 * gcj 标志告知渲染层底图坐标系：本底图为 false（WGS-84 直接显示）。
 */
object MapStyles {

    /** 可选样式：值（OpenFreeMap 官方标识，仅内部用） -> 展示名（设置页，统一两字） */
    val STYLES: List<Pair<String, String>> = listOf(
        "bright" to "亮色",
        "positron" to "浅色",
        "dark" to "暗色",
        "fiord" to "深蓝",
    )

    const val DEFAULT_STYLE = "bright"

    data class StyleSpec(val url: String, val gcj: Boolean)

    /** 返回可直接 setStyle 的样式 URL；同时把样式写入 filesDir/style-active.json */
    fun styleSpec(ctx: Context): StyleSpec {
        val style = Prefs.mapStyle.let { v -> if (STYLES.any { it.first == v }) v else DEFAULT_STYLE }
        val json = runCatching {
            ctx.assets.open("styles/ofm_$style.json").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        if (json.isNotBlank()) {
            return StyleSpec(writeStyle(ctx, json), gcj = false)
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