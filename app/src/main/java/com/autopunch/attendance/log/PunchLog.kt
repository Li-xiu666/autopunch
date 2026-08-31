package com.autopunch.attendance.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PunchLog {

    private const val MAX_LINES = 500
    private const val FILE_NAME = "punch_log.txt"

    @Synchronized
    fun append(context: Context, line: String) {
        val f = file(context)
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        runCatching {
            val history = f.readLines().takeLast(MAX_LINES - 1)
            f.writeText((history + "[$ts] $line").joinToString("\n") + "\n")
        }
    }

    @Synchronized
    fun read(context: Context): String =
        runCatching { file(context).readText() }.getOrDefault("")

    private fun file(context: Context): File =
        File(context.filesDir, FILE_NAME)
}