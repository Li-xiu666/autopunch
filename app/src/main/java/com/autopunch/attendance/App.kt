package com.autopunch.attendance

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannel(CHANNEL_KEEPALIVE, "保活通知", NotificationManager.IMPORTANCE_LOW)
        createChannel(CHANNEL_ALERT, "打卡结果", NotificationManager.IMPORTANCE_HIGH)
    }

    private fun createChannel(id: String, name: String, importance: Int) {
        val channel = NotificationChannel(id, name, importance)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_KEEPALIVE = "autopunch_keepalive"
        const val CHANNEL_ALERT = "autopunch_alert"
    }
}