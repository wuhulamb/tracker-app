package com.xu.locationtracker.util

import java.util.Locale

/** 距上次定位的秒数 → 人类可读文本："7 秒前 / 3 分钟前 / 1 小时前" */
fun lastFixAgeText(ageSec: Long): String = when {
    ageSec < 60 -> "$ageSec 秒前"
    ageSec < 3600 -> "${ageSec / 60} 分钟前"
    else -> "${ageSec / 3600} 小时前"
}

/** 时长毫秒 → "h:mm:ss"（顶部卡片/通知共用） */
fun fmtDur(ms: Long): String {
    if (ms <= 0) return "0:00:00"
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}