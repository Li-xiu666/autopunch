package com.autopunch.attendance.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.autopunch.attendance.UnlockActivity
import com.autopunch.attendance.config.Prefs
import java.util.Calendar
import kotlin.random.Random

object PunchScheduler {

    const val REQUEST_PUNCH = 1001
    const val EXTRA_EXPECTED = "extra_expected"

    private const val JITTER_MS = 3L * 60L * 1000L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private fun startOfDay(millis: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    fun startOfToday(now: Long = System.currentTimeMillis()): Long = startOfDay(now)

    private fun deterministicJitter(dayStart: Long, index: Int): Long {
        val seed = dayStart * 31L + index * 1009L + 7L
        return Random(seed).nextLong(-JITTER_MS, JITTER_MS + 1)
    }

    fun exactForPoint(dayStart: Long, hour: Int, minute: Int, index: Int): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = dayStart
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 30)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis + deterministicJitter(dayStart, index)
    }

    fun firstScheduleDay(startDate: Long, now: Long = System.currentTimeMillis()): Long {
        val today = startOfDay(now)
        return if (startDate > 0 && startDate > today) startOfDay(startDate) else today
    }

    fun schedule(context: Context) {
        val points = Prefs.getPunchPoints(context)
        if (points.isEmpty()) {
            Prefs.setNextPunch(context, 0L)
            cancel(context)
            return
        }

        val now = System.currentTimeMillis()
        val today = startOfDay(now)
        val startDate = Prefs.getStartDate(context)
        val futureStart = startDate > 0 && startDate > today

        var day = Prefs.getScheduleDay(context)
        var index = Prefs.getScheduleIndex(context)
        if (index >= points.size) index = 0
        if (day <= 0 || futureStart) {
            day = firstScheduleDay(startDate, now)
            index = 0
        }

        var guard = 0
        while (guard++ < 64) {
            val p = points[index]
            val exact = exactForPoint(day, p.first, p.second, index)
            if (exact > now) {
                Prefs.setScheduleDay(context, day)
                Prefs.setScheduleIndex(context, index)
                Prefs.setNextPunch(context, exact)
                arm(context, exact)
                return
            }
            index++
            if (index >= points.size) {
                index = 0
                day += DAY_MS
            }
        }

        val p = points[0]
        val exact = exactForPoint(day, p.first, p.second, 0)
        Prefs.setScheduleDay(context, day)
        Prefs.setScheduleIndex(context, 0)
        Prefs.setNextPunch(context, exact)
        arm(context, exact)
    }

    fun onPunchCompleted(context: Context) {
        schedule(context)
    }

    fun lastPunchExactToday(points: List<Pair<Int, Int>>, now: Long = System.currentTimeMillis()): Long {
        if (points.isEmpty()) return 0L
        val today = startOfDay(now)
        var last = 0L
        points.forEachIndexed { index, p ->
            val exact = exactForPoint(today, p.first, p.second, index)
            if (exact <= now) last = exact
        }
        return last
    }

    private fun arm(context: Context, exact: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, exact)
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
        am.cancel(pendingIntent(context, 0L))
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

    private fun pendingIntent(context: Context, exact: Long): PendingIntent {
        val i = Intent(context, UnlockActivity::class.java).apply {
            if (exact > 0) putExtra(EXTRA_EXPECTED, exact)
        }
        return PendingIntent.getActivity(
            context, REQUEST_PUNCH, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}