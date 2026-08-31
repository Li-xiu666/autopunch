package com.autopunch.attendance.config

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    private const val NAME = "autopunch_prefs"

    const val DEFAULT_PACKAGE = "钉钉"
    const val DEFAULT_KEYWORDS = "下班打卡,上班打卡,打卡,签到"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getPunchPoints(c: Context): List<Pair<Int, Int>> =
        sp(c).getString("punch_points", "08:55").orEmpty()
            .split(',', '，', '、', ';', '；')
            .mapNotNull { seg ->
                val parts = seg.trim().split(':')
                val h = parts.getOrNull(0)?.toIntOrNull()
                val m = parts.getOrNull(1)?.toIntOrNull()
                if (h != null && m != null) h.coerceIn(0, 23) to m.coerceIn(0, 59) else null
            }
            .distinct()

    fun setPunchPoints(c: Context, points: List<Pair<Int, Int>>) =
        sp(c).edit().putString(
            "punch_points",
            points.distinct().joinToString(",") { "%02d:%02d".format(it.first, it.second) }
        ).apply()

    fun getStartDate(c: Context): Long = sp(c).getLong("start_date", 0L)
    fun setStartDate(c: Context, millis: Long) =
        sp(c).edit().putLong("start_date", millis).apply()

    fun getScheduleDay(c: Context): Long = sp(c).getLong("schedule_day", 0L)
    fun setScheduleDay(c: Context, millis: Long) =
        sp(c).edit().putLong("schedule_day", millis).apply()

    fun getScheduleIndex(c: Context): Int = sp(c).getInt("schedule_index", 0)
    fun setScheduleIndex(c: Context, v: Int) =
        sp(c).edit().putInt("schedule_index", v.coerceAtLeast(0)).apply()

    fun getTargetPackage(c: Context): String =
        sp(c).getString("target_package", DEFAULT_PACKAGE).orEmpty()

    fun setTargetPackage(c: Context, v: String) =
        sp(c).edit().putString("target_package", v.trim()).apply()

    fun getKeywords(c: Context): String =
        sp(c).getString("keywords", DEFAULT_KEYWORDS).orEmpty()

    fun setKeywords(c: Context, v: String) =
        sp(c).edit().putString("keywords", v.trim()).apply()

    fun getKeywordsList(c: Context): List<String> =
        getKeywords(c).split(',', '，', '、', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun getSmtpEmail(c: Context): String =
        sp(c).getString("smtp_email", "").orEmpty()

    fun setSmtpEmail(c: Context, v: String) =
        sp(c).edit().putString("smtp_email", v.trim()).apply()

    fun getSmtpCode(c: Context): String =
        sp(c).getString("smtp_code", "").orEmpty()

    fun setSmtpCode(c: Context, v: String) =
        sp(c).edit().putString("smtp_code", v.trim()).apply()

    fun getToEmail(c: Context): String =
        sp(c).getString("to_email", "").orEmpty()

    fun setToEmail(c: Context, v: String) =
        sp(c).edit().putString("to_email", v.trim()).apply()

    fun getNextPunch(c: Context): Long = sp(c).getLong("next_punch", 0L)
    fun setNextPunch(c: Context, millis: Long) =
        sp(c).edit().putLong("next_punch", millis).apply()

    fun setLastResult(c: Context, v: String) =
        sp(c).edit().putString("last_result", v).apply()

    fun getLastResult(c: Context): String =
        sp(c).getString("last_result", "").orEmpty()
}