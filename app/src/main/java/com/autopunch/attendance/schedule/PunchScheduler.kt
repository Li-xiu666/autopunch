package com.autopunch.attendance.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.autopunch.attendance.UnlockActivity
import com.autopunch.attendance.config.Prefs
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object PunchScheduler {

    const val REQUEST_PUNCH = 1001
    private const val JITTER_MS = 3L * 60L * 1000L

    fun computeExactTime(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 30)
            set(Calendar.MILLISECOND, 0)
        }
        var base = c.timeInMillis
        if (base <= now) base += TimeUnit.DAYS.toMillis(1)
        val jitter = Random.nextLong(-JITTER_MS, JITTER_MS + 1)
        return base + jitter
    }

    fun schedule(context: Context) {
        if (Prefs.getTargetPackage(context).trim().isEmpty()) return
        val exact = computeExactTime(Prefs.getHour(context), Prefs.getMinute(context))
        Prefs.setNextPunch(context, exact)

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, exact, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, exact, pi)
        } catch (_: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, exact, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= 31) {
            try {
                am.canScheduleExactAlarms()
            } catch (_: Exception) {
                true
            }
        } else true
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val i = Intent(context, UnlockActivity::class.java)
        return PendingIntent.getActivity(
            context, REQUEST_PUNCH, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}