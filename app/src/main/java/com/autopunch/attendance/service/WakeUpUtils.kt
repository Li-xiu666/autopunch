package com.autopunch.attendance.service

import android.content.Context
import android.os.PowerManager
import android.view.WindowManager

object WakeUpUtils {

    private const val SCREEN_ON_MS = 120_000L

    @Volatile
    private var held: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    fun holdScreen(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.FULL_WAKE_LOCK,
            "autopunch:screen"
        )
        lock.apply { if (isHeld) release() }
        release()
        held = lock
        try {
            lock.acquire(SCREEN_ON_MS)
        } catch (_: Exception) {
        }
    }

    fun release() {
        held?.let { runCatching { if (it.isHeld) it.release() } }
        held = null
    }

    fun showWhenLockedFlags(): Int =
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
}