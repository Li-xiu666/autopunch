package com.autopunch.attendance.config

import android.content.Context
import android.content.pm.PackageManager

object TargetResolver {

    fun resolve(context: Context): String? {
        val raw = Prefs.getTargetPackage(context).trim()
        if (raw.isEmpty()) return null
        if (looksLikePackage(raw) && isInstalled(context, raw)) return raw
        return resolveByAppName(context, raw)
    }

    fun looksLikePackage(name: String): Boolean =
        name.isNotEmpty() &&
            name.contains('.') &&
            name.all { it.isLetter() || it.isDigit() || it == '.' || it == '_' }

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)

    fun resolveByAppName(context: Context, appName: String): String? {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0).asSequence()
        for (app in apps) {
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() ?: continue
            if (label.equals(appName, ignoreCase = true)) return app.packageName
        }
        return null
    }
}