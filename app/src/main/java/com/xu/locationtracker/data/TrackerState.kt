package com.xu.locationtracker.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 进程内共享的轨迹状态（服务写、UI 读）。
 * points 缓存当天已保存的点；服务每秒最多追加几次，全天约几千个点，开销可忽略。
 */
object TrackerState {
    /** 当前是否在记录（服务存活且未停止） */
    val isRecording = MutableStateFlow(false)

    /** 是否处于静止降频 */
    val isStatic = MutableStateFlow(false)

    /** 当前运动档位文案（静止/步行/骑行/驾车/高速）；服务写、UI 读；空串表示尚未测速 */
    val motionLabel = MutableStateFlow("")

    /** 当天已记录的点（仅今日实时流，不受回放影响） */
    val points = MutableStateFlow<List<TrackPoint>>(emptyList())

    /** 历史回放选中日期的点 */
    val historyPoints = MutableStateFlow<List<TrackPoint>>(emptyList())

    /** 当前显示轨迹对应的日期，null = 今天 */
    val viewDay = MutableStateFlow<String?>(null)

    /** 最近一次从系统收到定位回调的时间戳（0 表示尚未收到） */
    val lastFixAt = MutableStateFlow(0L)

    /** 最近一次可用 fix 的 WGS-84 坐标（通过精度闸门，含网络兜底）；null = 尚未收到 */
    val lastFixLoc = MutableStateFlow<Pair<Double, Double>?>(null)
}