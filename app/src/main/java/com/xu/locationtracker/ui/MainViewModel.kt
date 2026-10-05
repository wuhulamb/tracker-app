package com.xu.locationtracker.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xu.locationtracker.data.AppGraph
import com.xu.locationtracker.data.Prefs
import com.xu.locationtracker.data.TrackerState
import com.xu.locationtracker.data.dayKeyOf
import com.xu.locationtracker.service.TrackingService
import com.xu.locationtracker.util.Exporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class DaySummary(val day: String, val count: Int)

    val isRecording = TrackerState.isRecording
    val isStatic = TrackerState.isStatic
    val points = TrackerState.points
    val historyPoints = TrackerState.historyPoints
    val viewDay = TrackerState.viewDay
    val lastFixAt = TrackerState.lastFixAt

    val history: StateFlow<List<DaySummary>> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(snapshotDays())
            delay(15_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshotDays())

    private fun snapshotDays(): List<DaySummary> {
        val ctx = getApplication<Application>()
        if (!AppGraph.isReady) return emptyList()
        return AppGraph.store.listDays().map { day -> DaySummary(day, countLines(day)) }
    }

    private fun countLines(day: String): Int {
        return runCatching {
            val f = java.io.File(AppGraph.store.dir, "$day.jsonl")
            if (!f.exists()) 0 else f.readLines().count { it.isNotBlank() }
        }.getOrDefault(0)
    }

    // ---------- 记录控制 ----------

    fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 全天模式 auto 启动/恢复 */
    fun autoStartIfNeeded() {
        val ctx = getApplication<Application>()
        if (Prefs.mode != Prefs.MODE_AUTO || TrackerState.isRecording.value) return
        if (Prefs.autoStoppedForDay == dayKeyOf(System.currentTimeMillis())) return
        if (!hasFineLocation()) return
        TrackingService.start(ctx, TrackingService.ACTION_START)
    }

    fun setMode(mode: String) {
        Prefs.mode = mode
        val ctx = getApplication<Application>()
        if (mode == Prefs.MODE_AUTO) {
            autoStartIfNeeded()
        } else {
            // 切到手动：先停掉当前记录，由用户在地图页手动开始
            if (TrackerState.isRecording.value) stopService()
        }
    }

    /** 手动模式开始 */
    fun startManual() {
        Prefs.manualRecording = true
        TrackingService.start(getApplication(), TrackingService.ACTION_START)
    }

    /** 手动模式停止 */
    fun stopManual() {
        Prefs.manualRecording = false
        stopService()
    }

    /** 全天模式：结束今日记录（明日自动恢复） */
    fun endTodayAuto() {
        Prefs.autoStoppedForDay = dayKeyOf(System.currentTimeMillis())
        stopService()
    }

    /** 全天模式：恢复今日记录 */
    fun resumeTodayAuto() {
        Prefs.autoStoppedForDay = ""
        autoStartIfNeeded()
    }

    /** 参数变更后让服务按新参数重启定位订阅 */
    fun applyParams() {
        if (TrackerState.isRecording.value) {
            TrackingService.start(getApplication(), TrackingService.ACTION_REFRESH)
        }
    }

    private fun stopService() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, TrackingService::class.java))
    }

    // ---------- 历史 ----------

    fun showDay(day: String?) {
        TrackerState.viewDay.value = day
        if (day == null) {
            TrackerState.historyPoints.value = emptyList()
            return
        }
        viewModelScope.launch {
            TrackerState.historyPoints.value = AppGraph.store.readDay(day)
        }
    }

    // ---------- 导出 ----------

    fun exportGpx(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val uri = Exporter.exportGpx(getApplication())
            onResult(uri)
        }
    }

    fun exportCsv(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val uri = Exporter.exportCsv(getApplication())
            onResult(uri)
        }
    }

    fun deleteAll(onDone: () -> Unit) {
        viewModelScope.launch {
            AppGraph.store.deleteAll()
            TrackerState.points.value = emptyList()
            onDone()
        }
    }
}