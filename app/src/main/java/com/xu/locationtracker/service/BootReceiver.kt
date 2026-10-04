package com.xu.locationtracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.xu.locationtracker.data.Prefs
import com.xu.locationtracker.data.dayKeyOf

/**
 * 开机自启。
 * 说明：Android 12+ 从后台直接启动前台服务受限，但"忽略电池优化"白名单内的应用
 * 是官方豁免项之一；华为系统还会额外拦截 BOOT_COMPLETED 广播，需要在
 * 设置-应用-启动管理 中允许自启动。所以这里尽力而为，失败静默。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val today = dayKeyOf(System.currentTimeMillis())
        if (Prefs.mode != Prefs.MODE_AUTO || Prefs.autoStoppedForDay == today) return

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempted = pm.isIgnoringBatteryOptimizations(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exempted) {
            // 未入白名单时尝试启动，失败则由系统抛异常被 start() 吞掉
        }
        TrackingService.start(context, TrackingService.ACTION_BOOT)
    }
}