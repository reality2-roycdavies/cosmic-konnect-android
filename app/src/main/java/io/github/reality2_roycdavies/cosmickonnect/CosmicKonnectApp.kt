package io.github.reality2_roycdavies.cosmickonnect

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CosmicKonnectApp : Application() {

    companion object {
        const val CHANNEL_SERVICE = "cosmic_konnect_service"
        const val CHANNEL_NOTIFICATIONS = "cosmic_konnect_notifications"

        lateinit var instance: CosmicKonnectApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Service channel (for foreground service)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Connection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Cosmic Konnect is running"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // Notifications channel (for pings, alerts)
            val notificationsChannel = NotificationChannel(
                CHANNEL_NOTIFICATIONS,
                "Device Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from connected devices"
            }
            notificationManager.createNotificationChannel(notificationsChannel)
        }
    }
}
