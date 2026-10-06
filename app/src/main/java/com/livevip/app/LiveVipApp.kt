package com.livevip.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

/**
 * IMPORTANT STARTUP RULE:
 * Application.onCreate() only initializes lightweight components.
 * No camera, no microphone, no encoders, no RTMP, no services, no network
 * requests happen here. The Home screen renders first; every heavy
 * subsystem starts lazily on explicit user action.
 */
class LiveVipApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Dark premium theme — always (graphite/black, purple/cyan accents).
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Lightweight: register the notification channel used by the
        // foreground streaming service (cheap, no service is started).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                STREAM_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val STREAM_CHANNEL_ID = "live_vip_stream"
    }
}
