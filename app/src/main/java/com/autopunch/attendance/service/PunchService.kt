package com.autopunch.attendance.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autopunch.attendance.config.Prefs
import com.autopunch.attendance.config.TargetResolver
import com.autopunch.attendance.log.PunchLog
import com.autopunch.attendance.mail.MailSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PunchService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var task: PunchTask? = null
    private var finishOnce = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        PunchLog.append(this, "[Svc] 无障碍连接成功")
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRIGGER) {
            val test = intent.getBooleanExtra(EXTRA_TEST, false)
            if (!isAccessibilityEnabled()) {
                reportUnavailable()
                return START_NOT_STICKY
            }
            startPunch(test)
        }
        return START_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val t = task ?: return
        if (t.finished) return
        val pkg = event.packageName?.toString()
        if (pkg != t.targetPackage) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handler.post { tryClickOnce(t) }
            }
        }
    }

    private fun startPunch(test: Boolean) {
        val pkg = TargetResolver.resolve(this)
        if (pkg == null) {
            val raw = Prefs.getTargetPackage(this)
            PunchLog.append(this, "[Punch] 无法识别目标App: $raw")
            finishReport(
                subject = "❌ 打卡失败 [#target]",
                body = buildString {
                    append("时间: ").append(nowStr()).append('\n')
                    append("原因: ").append("无法识别目标App($raw)，请检查应用名称或改为包名").append('\n')
                    append("类型: ").append(if (test) "测试" else "计划").append('\n')
                }
            )
            return
        }
        PunchLog.append(
            this,
            "[Punch] 开始 ${if (test) "测试" else "计划"}打卡: app=$pkg (${Prefs.getTargetPackage(this)}) keywords=${Prefs.getKeywords(this)}"
        )
        WakeUpUtils.holdScreen(this)
        val t = PunchTask(
            targetPackage = pkg,
            keywords = Prefs.getKeywordsList(this),
            doneKeywords = listOf("打卡成功", "已打卡", "打卡完成", "考勤完成"),
            test = test,
            timeWindowStart = Prefs.getNextPunch(this),
            deadline = System.currentTimeMillis() + TIMEOUT_MS
        )
        task = t
        finishOnce = false
        handler.post(loopRunnable)
    }

    private val loopRunnable = object : Runnable {
        override fun run() {
            val t = task ?: return
            if (t.finished) return

            if (System.currentTimeMillis() > t.deadline) {
                fail("#timeout", "执行超时(${TIMEOUT_MS / 1000}s)，未捕获到打卡完成")
                return
            }

            if (!t.test && nowOutsideWindow(t)) {
                fail("#window", "当前时间超出预定时间窗，中止本次打卡")
                return
            }

            val root = rootInActiveWindow
            val currentPkg = root?.packageName?.toString()

            if (root == null || currentPkg != t.targetPackage) {
                if (++t.launchTries > MAX_LAUNCH_TRIES) {
                    fail("#launch", "目标App $currentPkg 未出现在前台(${MAX_LAUNCH_TRIES} 次启动失败)")
                    return
                }
                PunchLog.append(this@PunchService, "[Punch] 正在拉起目标App... (${t.launchTries})")
                relaunchApp()
                handler.postDelayed(this, LAUNCH_WAIT_MS)
                return
            }

            if (NodeFinder.hasDone(root, t.doneKeywords)) {
                success(t, root)
                return
            }

            if (tryClickOnce(t)) {
                if (t.finished) return
                handler.postDelayed(this, CLICK_COOL_DOWN_MS)
            } else {
                if (++t.emptyChecks > MAX_EMPTY_CHECK) {
                    fail("#node", "在目标页未找到打卡节点，可能页面布局变化或加载失败")
                    return
                }
                handler.postDelayed(this, RETRY_MS)
            }
        }
    }

    private fun tryClickOnce(t: PunchTask): Boolean {
        if (t.finished) return false
        val root = rootInActiveWindow ?: return false
        if (NodeFinder.hasDone(root, t.doneKeywords)) {
            success(t, root)
            return true
        }
        val candidates = NodeFinder.collect(root, t.keywords)
        val next = candidates.drop(t.clickedIndex).firstOrNull() ?: return false
        t.clickedIndex++
        val clickable = NodeFinder.closestClickable(next)
        if (clickable == null) {
            PunchLog.append(this, "[Punch] 候选 #${t.clickedIndex} 不可点击，跳过")
            return true
        }
        val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        PunchLog.append(this, "[Punch] 点击候选 #${t.clickedIndex} (${textOf(next)}) -> $ok")
        return true
    }

    private fun relaunchApp() {
        val pkg = TargetResolver.resolve(this) ?: return
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(launch)
            }
        }
    }

    private fun nowOutsideWindow(t: PunchTask): Boolean {
        val now = System.currentTimeMillis()
        if (t.timeWindowStart <= 0) return false
        val earliest = t.timeWindowStart - 2 * 60 * 1000L
        val latest = t.timeWindowStart + 10 * 60 * 1000L
        return now < earliest || now > latest
    }

    private fun success(t: PunchTask, root: AccessibilityNodeInfo?) {
        if (t.finished || finishOnce) return
        t.finished = true
        finishOnce = true
        val time = nowStr()
        val texts = root?.let { NodeFinder.sampleTexts(it, 12) }?.joinToString(" | ").orEmpty()
        PunchLog.append(this, "[Punch] 打卡成功 $time")
        finishReport(
            subject = "✅ 打卡成功",
            body = buildString {
                append("时间: ").append(time).append('\n')
                append("类型: ").append(if (t.test) "测试" else "计划").append('\n')
                append("应用: ").append(t.targetPackage).append('\n')
                append("当前页面采样: ").append(texts.ifEmpty { "(空)" }).append('\n')
            }
        )
    }

    private fun fail(code: String, reason: String) {
        val t = task ?: return
        if (t.finished || finishOnce) return
        t.finished = true
        finishOnce = true
        PunchLog.append(this, "[Punch] 失败[$code] $reason")
        finishReport(
            subject = "❌ 打卡失败 [$code]",
            body = buildString {
                append("时间: ").append(nowStr()).append('\n')
                append("原因: ").append(reason).append('\n')
                append("应用: ").append(Prefs.getTargetPackage(this@PunchService)).append('\n')
            }
        )
    }

    private fun reportUnavailable() {
        PunchLog.append(this, "[Punch] 无障碍服务未开启，无法执行")
        failReport("无障碍服务未开启，打卡无法自动执行。请到 系统设置->无障碍->自动打卡助手 开启后重试。")
    }

    private fun finishReport(subject: String, body: String) {
        Prefs.setLastResult(this, "$subject\n$body")
        task = null
        WakeUpUtils.release()
        handler.removeCallbacks(loopRunnable)

        if (MailSender.isConfigured(this)) {
            scope.launch {
                val r = MailSender.send(this@PunchService, subject, body)
                PunchLog.append(this@PunchService, "[Mail] $r")
            }
        } else {
            PunchLog.append(this, "[Mail] 未配置邮箱，跳过发送。$subject")
        }
    }

    private fun failReport(subject: String) {
        Prefs.setLastResult(this, subject)
        if (MailSender.isConfigured(this)) {
            scope.launch {
                val r = MailSender.send(this@PunchService, subject, nowStr())
                PunchLog.append(this@PunchService, "[Mail] $r")
            }
        }
        handler.postDelayed({ stopSelf() }, 1500)
    }

    private fun isAccessibilityEnabled(): Boolean =
        runCatching {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains(packageName, true) == true
        }.getOrDefault(false)

    private fun nowStr(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun textOf(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString().orEmpty()

    companion object {
        const val ACTION_TRIGGER = "com.autopunch.attendance.TRIGGER"
        const val EXTRA_TEST = "extra_test"

        private const val TIMEOUT_MS = 75_000L
        private const val MAX_LAUNCH_TRIES = 3
        private const val MAX_EMPTY_CHECK = 4
        private const val LAUNCH_WAIT_MS = 1_500L
        private const val CLICK_COOL_DOWN_MS = 2_200L
        private const val RETRY_MS = 1_800L

        fun start(context: Context, test: Boolean = false) {
            val i = Intent(context, PunchService::class.java)
                .setAction(ACTION_TRIGGER)
                .putExtra(EXTRA_TEST, test)
            context.startService(i)
        }
    }

    private class PunchTask(
        val targetPackage: String,
        val keywords: List<String>,
        val doneKeywords: List<String>,
        val test: Boolean,
        val timeWindowStart: Long,
        val deadline: Long
    ) {
        @Volatile var finished: Boolean = false
        var clickedIndex: Int = 0
        var launchTries: Int = 0
        var emptyChecks: Int = 0
    }
}