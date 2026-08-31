package com.autopunch.attendance.config

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    private const val NAME = "autopunch_prefs"

    const val DEFAULT_PACKAGE = "com.alibaba.android.rimet"
    const val DEFAULT_KEYWORDS = "下班打卡,上班打卡,打卡,签到"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getHour(c: Context): Int = sp(c).getInt("hour", 8)
    fun setHour(c: Context, v: Int) = sp(c).edit().putInt("hour", v.coerceIn(0, 23)).apply()

    fun getMinute(c: Context): Int = sp(c).getInt("minute", 55)
    fun setMinute(c: Context, v: Int) = sp(c).edit().putInt("minute", v.coerceIn(0, 59)).apply()

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