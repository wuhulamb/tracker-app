package com.xu.locationtracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

/**
 * 轨迹存储：纯文本文件，无数据库。
 *
 * 存储位置： /sdcard/Android/data/com.xu.locationtracker/files/tracks/
 *   - 每个日期一个 JSONL 文件： 2025-01-01.jsonl，每行一个点
 *   - adb pull /sdcard/Android/data/com.xu.locationtracker/files/tracks 可直接拷贝
 *   - 应用内"导出"可一键生成 GPX / CSV 到系统"下载"目录
 */
class TrackStore(val dir: File) {

    private val mutex = Mutex()
    private val writers = HashMap<String, FileWriter>()

    /** 追加一个点（按点自身日期落到对应文件） */
    suspend fun append(p: TrackPoint) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = fileOf(dayKeyOf(p.t))
            val w = writers.getOrPut(dayKeyOf(p.t)) {
                f.parentFile?.mkdirs()
                FileWriter(f, true)
            }
            w.append(p.toJson()).append('\n')
            w.flush()
        }
    }

    /** 读取某天全部点 */
    suspend fun readDay(day: String): List<TrackPoint> = withContext(Dispatchers.IO) {
        val f = fileOf(day)
        if (!f.exists()) return@withContext emptyList()
        f.readLines().mapNotNull { TrackPoint.fromJson(it) }
    }

    /** 所有日期，倒序 */
    fun listDays(): List<String> = runCatching {
        dir.listFiles { f -> f.isFile && f.extension == "jsonl" }?.map { it.nameWithoutExtension }?.sortedDescending() ?: emptyList()
    }.getOrDefault(emptyList())

    fun existingDay(day: String): Boolean = fileOf(day).exists()

    /** 关闭并清空所有写句柄（Service 销毁时调用） */
    fun closeAll() {
        writers.values.forEach { runCatching { it.close() } }
        writers.clear()
    }

    /** 删除某天文件 */
    suspend fun deleteDay(day: String) = withContext(Dispatchers.IO) {
        fileOf(day).delete()
    }

    /** 删除全部数据 */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeAll()
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun fileOf(day: String): File = File(dir, "$day.jsonl")
}

/** 全局对象图，在 [com.xu.locationtracker.LocationApp] 中初始化 */
object AppGraph {
    lateinit var store: TrackStore
    val isReady: Boolean get() = ::store.isInitialized
}