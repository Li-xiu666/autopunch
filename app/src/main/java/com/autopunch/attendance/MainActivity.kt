package com.autopunch.attendance

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autopunch.attendance.config.Prefs
import com.autopunch.attendance.config.TargetResolver
import com.autopunch.attendance.databinding.ActivityMainBinding
import com.autopunch.attendance.log.PunchLog
import com.autopunch.attendance.mail.MailSender
import com.autopunch.attendance.schedule.PunchScheduler
import com.autopunch.attendance.service.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val exactAlarmPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshNext() }

    private val pickApp =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) {
                val pkg = r.data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE)
                val label = r.data?.getStringExtra(AppPickerActivity.EXTRA_LABEL)
                if (!pkg.isNullOrEmpty()) {
                    Prefs.setTargetPackage(this, pkg)
                    binding.etPackage.setText(pkg)
                    PunchScheduler.schedule(this)
                    refreshNext()
                    binding.tvStatus.text = "已选择: $label ($pkg)"
                    toast("目标应用已设置")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRuntimePermissions()
        loadPrefs()
        bindActions()
        KeepAliveService.start(this)
        refreshNext()
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !binding.let {
                PunchScheduler.canScheduleExact(this)
            }
        ) {
            AlertDialog.Builder(this)
                .setTitle("需要精确闹钟权限")
                .setMessage("为保证准时打卡，请在系统设置中允许「闹钟和提醒」权限。")
                .setPositiveButton("去设置") { _, _ ->
                    runCatching {
                        exactAlarmPermission.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
                .setNegativeButton("暂不", null)
                .show()
        }
    }

    private fun loadPrefs() {
        val points = Prefs.getPunchPoints(this)
        for (i in 0 until 5) {
            val p = points.getOrNull(i)
            enableBoxes[i].isChecked = p != null
            if (p != null) {
                hourEdits[i].setText(p.first.toString())
                minuteEdits[i].setText(p.second.toString())
            } else {
                hourEdits[i].setText("")
                minuteEdits[i].setText("")
            }
        }
        val sd = Prefs.getStartDate(this)
        binding.tvStartDate.text = if (sd > 0) fmtDate(sd) else "未设置（立即生效）"
        binding.etPackage.setText(Prefs.getTargetPackage(this))
        binding.etKeywords.setText(Prefs.getKeywords(this))
        binding.etSmtpEmail.setText(Prefs.getSmtpEmail(this))
        binding.etSmtpCode.setText(Prefs.getSmtpCode(this))
        binding.etToEmail.setText(Prefs.getToEmail(this))
    }

    private val hourEdits get() = arrayOf(binding.etHour1, binding.etHour2, binding.etHour3, binding.etHour4, binding.etHour5)
    private val minuteEdits get() = arrayOf(binding.etMinute1, binding.etMinute2, binding.etMinute3, binding.etMinute4, binding.etMinute5)
    private val enableBoxes get() = arrayOf(binding.cb1, binding.cb2, binding.cb3, binding.cb4, binding.cb5)

    private fun bindActions() {
        binding.btnSaveSchedule.setOnClickListener { saveAndSchedule() }
        binding.btnTest.setOnClickListener { onTestPunch() }
        binding.btnPickApp.setOnClickListener {
            runCatching {
                pickApp.launch(Intent(this, AppPickerActivity::class.java))
            }
        }
        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnBattery.setOnClickListener { openBatterySettings() }
        binding.btnLog.setOnClickListener { showLog() }
        binding.btnTestMail.setOnClickListener { testMail() }
        binding.btnPickDate.setOnClickListener { pickStartDate() }
        binding.btnClearDate.setOnClickListener {
            Prefs.setStartDate(this, 0L)
            binding.tvStartDate.text = "未设置（立即生效）"
            toast("已清除开始日期（下次保存后立即生效）")
        }
    }

    private fun pickStartDate() {
        val cal = Calendar.getInstance()
        runCatching {
            android.app.DatePickerDialog(
                this,
                { _, y, mo, d ->
                    val c = Calendar.getInstance().apply {
                        set(y, mo, d, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    Prefs.setStartDate(this@MainActivity, c.timeInMillis)
                    binding.tvStartDate.text = fmtDate(c.timeInMillis)
                    toast("已设置开始日期")
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun saveAndSchedule() {
        val points = ArrayList<Pair<Int, Int>>()
        for (i in 0 until 5) {
            if (!enableBoxes[i].isChecked) continue
            val h = hourEdits[i].text.toString().toIntOrNull()
            val m = minuteEdits[i].text.toString().toIntOrNull()
            if (h != null && m != null) {
                points.add(h.coerceIn(0, 23) to m.coerceIn(0, 59))
            }
        }
        if (points.isEmpty()) {
            toast("请至少勾选并填写一个打卡时间")
            return
        }
        Prefs.setPunchPoints(this, points)
        Prefs.setTargetPackage(this, binding.etPackage.text.toString())
        Prefs.setKeywords(this, binding.etKeywords.text.toString())
        Prefs.setSmtpEmail(this, binding.etSmtpEmail.text.toString())
        Prefs.setSmtpCode(this, binding.etSmtpCode.text.toString())
        Prefs.setToEmail(this, binding.etToEmail.text.toString())

        if (Prefs.getTargetPackage(this).isEmpty()) {
            toast("请填写考勤应用名称或包名")
            return
        }
        if (!PunchScheduler.canScheduleExact(this)) {
            toast("未授予精确闹钟权限，将使用普通闹钟（可能存在偏差）")
        }
        PunchScheduler.schedule(this)
        refreshNext()
        val m = TargetResolver.resolveMatch(this)
        if (m != null) {
            binding.tvStatus.text = "已识别应用: ${m.label ?: m.packageName} (${m.packageName})"
            toast("已保存并调度下一次打卡")
        } else {
            val raw = Prefs.getTargetPackage(this)
            binding.tvStatus.text = "⚠️ 未识别到「$raw」"
            toast("未识别到该应用，请在相似列表中选择")
            showCandidateDialog(raw)
        }
    }

    private fun showCandidateDialog(query: String) {
        val candidates = TargetResolver.searchCandidates(this, query)
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("未找到相似应用")
                .setMessage("手机上没有名称或包名包含「$query」的应用。\n请用「选择应用」按钮从已安装应用列表直接挑选。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val items = candidates.map { "${it.label ?: it.packageName} (${it.packageName})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("请选择要打卡的应用")
            .setItems(items) { _, which ->
                val m = candidates[which]
                Prefs.setTargetPackage(this, m.packageName)
                binding.etPackage.setText(m.packageName)
                binding.tvStatus.text = "已选择: ${m.label} (${m.packageName})"
                PunchScheduler.schedule(this)
                refreshNext()
                toast("目标应用已设置")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onTestPunch() {
        saveAndSchedule()
        if (TargetResolver.resolve(this) == null) {
            toast("目标应用未识别，请先选择应用")
            return
        }
        if (!isAccessibilityEnabled()) {
            toast("请先开启无障碍服务")
            openAccessibilitySettings()
            return
        }
        toast("5 秒后开始测试流程")
        scope.launch {
            kotlinx.coroutines.delay(5_000L)
            UnlockActivity.startForTest(this@MainActivity)
        }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun openBatterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已在电池优化白名单中")
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun testMail() {
        saveAndSchedule()
        if (!MailSender.isConfigured(this)) {
            toast("请先填写邮箱与授权码")
            return
        }
        toast("正在发送测试邮件...")
        scope.launch {
            val subject = "自动打卡助手 测试邮件 ${ts()}"
            val r = MailSender.send(this@MainActivity, subject, "这是一封测试邮件\n如果你的设备收到它，说明SMTP配置正确。")
            if (r == "ok") toast("测试邮件发送成功") else toast(r)
        }
    }

    private fun showLog() {
        val logText = PunchLog.read(this).ifEmpty { "暂无日志" }
        AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setMessage(logText)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun refreshNext() {
        val next = Prefs.getNextPunch(this)
        val points = Prefs.getPunchPoints(this)
        val pointsStr = if (points.isEmpty()) "未设置" else points.joinToString("  ") { "%02d:%02d".format(it.first, it.second) }
        val startDate = Prefs.getStartDate(this)
        val startStr = if (startDate > 0) "自 ${fmtDate(startDate)} 起" else "立即生效"
        val s = if (next > 0) ts(next) else "尚未调度"
        binding.tvNext.text = "打卡点(${points.size}): $pointsStr\n开始日期: $startStr\n下一次打卡时刻: $s"
        val last = Prefs.getLastResult(this)
        binding.tvStatus.text = if (last.isEmpty()) "" else "上次结果:\n$last"
    }

    private fun fmtDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))

    private fun isAccessibilityEnabled(): Boolean =
        runCatching {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains(packageName, true) == true
        }.getOrDefault(false)

    private fun ts(ms: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}