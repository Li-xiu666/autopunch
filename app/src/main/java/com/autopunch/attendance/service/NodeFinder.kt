package com.autopunch.attendance.service

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

object NodeFinder {

    fun collect(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        matchViewId: Boolean = true
    ): List<AccessibilityNodeInfo> {
        if (keywords.isEmpty()) return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 4000) {
            val n = queue.removeFirst()
            val text = n.text?.toString().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            val id = if (matchViewId) n.viewIdResourceName.orEmpty() else ""
            if (keywords.any { text.contains(it, true) || desc.contains(it, true) || id.contains(it, true) }) {
                out.add(n)
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    fun closestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val visited = HashSet<AccessibilityNodeInfo>()
        var cursor: AccessibilityNodeInfo? = node
        while (cursor != null) {
            if (!visited.add(cursor)) break
            if (cursor.isClickable) return cursor
            cursor = cursor.parent
        }
        return null
    }

    fun hasDone(root: AccessibilityNodeInfo, doneKeywords: List<String>): Boolean {
        if (doneKeywords.isEmpty()) return false
        return collect(root, doneKeywords, matchViewId = false).isNotEmpty()
    }

    fun sampleTexts(root: AccessibilityNodeInfo, limit: Int = 24): List<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 4000 && out.size < limit) {
            val n = queue.removeFirst()
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) out.add(text)
            else if (desc.isNotEmpty()) out.add(desc)
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return out.toList()
    }
}