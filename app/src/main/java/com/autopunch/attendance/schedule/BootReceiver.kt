package com.autopunch.attendance.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autopunch.attendance.UnlockActivity
import com.autopunch.attendance.config.Prefs
import com.autopunch.attendance.service.KeepAliveService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {}
            else -> return
        }

        KeepAliveService.start(context)
        PunchScheduler.schedule(context)

        val scheduled = Prefs.getNextPunch(context)
        val now = System.currentTimeMillis()
        val withinCatch = now >= scheduled && now <= scheduled + CATCH_WINDOW_MS
        if (withinCatch) {
            context.startActivity(
                Intent(context, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    companion object {
        private const val CATCH_WINDOW_MS = 20 * 60 * 1000L
    }
}