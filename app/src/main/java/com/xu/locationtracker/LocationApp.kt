package com.xu.locationtracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.xu.locationtracker.data.AppGraph
import com.xu.locationtracker.data.Prefs
import com.xu.locationtracker.data.TrackStore
import com.xu.locationtracker.service.TrackingService
import org.maplibre.android.MapLibre
import java.io.File

class LocationApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        AppGraph.store = TrackStore(File(getExternalFilesDir(null), "tracks"))
        MapLibre.getInstance(this)
        createTrackingChannel()
    }

    private fun createTrackingChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            TrackingService.CHANNEL_ID,
            "轨迹记录",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "前台服务常驻通知，显示轨迹记录状态"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}