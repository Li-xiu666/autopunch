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

        val now = System.currentTimeMillis()
        val lastPunch = PunchScheduler.lastPunchExactToday(Prefs.getPunchPoints(context), now)
        if (lastPunch > 0 && now >= lastPunch && now <= lastPunch + CATCH_WINDOW_MS) {
            context.startActivity(
                Intent(context, UnlockActivity::class.java)
                    .putExtra(PunchScheduler.EXTRA_EXPECTED, lastPunch)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    companion object {
        private const val CATCH_WINDOW_MS = 20 * 60 * 1000L
    }
}