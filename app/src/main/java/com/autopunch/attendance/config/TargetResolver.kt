package com.autopunch.attendance.config

import android.content.Context
import android.content.pm.PackageManager

object TargetResolver {

    data class Match(val packageName: String, val label: String?)

    fun resolve(context: Context): String? = resolveMatch(context)?.packageName

    fun resolveMatch(context: Context): Match? {
        val raw = Prefs.getTargetPackage(context).trim()
        if (raw.isEmpty()) return null

        if (looksLikePackage(raw) && isInstalled(context, raw)) {
            return Match(raw, labelOf(context, raw))
        }

        val apps = allApps(context)
        apps.firstOrNull { raw.equals(it.label, ignoreCase = true) }?.let { return it }
        apps.firstOrNull { it.label?.contains(raw, ignoreCase = true) == true }?.let { return it }
        apps.firstOrNull { it.packageName.contains(raw, ignoreCase = true) }?.let { return it }
        return null
    }

    fun searchCandidates(context: Context, query: String, limit: Int = 15): List<Match> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return allApps(context)
            .filter { it.label?.contains(q, ignoreCase = true) == true || it.packageName.contains(q, ignoreCase = true) }
            .take(limit)
    }

    private fun allApps(context: Context): List<Match> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0).mapNotNull { app ->
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull()
            Match(app.packageName, label)
        }.sortedBy { it.label }
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

    fun labelOf(context: Context, packageName: String): String? =
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
}