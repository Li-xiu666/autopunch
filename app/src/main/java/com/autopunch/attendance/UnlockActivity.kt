package com.autopunch.attendance

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.autopunch.attendance.config.Prefs
import com.autopunch.attendance.config.TargetResolver
import com.autopunch.attendance.log.PunchLog
import com.autopunch.attendance.schedule.PunchScheduler
import com.autopunch.attendance.service.PunchService
import com.autopunch.attendance.service.WakeUpUtils

class UnlockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WakeUpUtils.showWhenLockedFlags())
        setContentView(R.layout.activity_unlock)

        PunchLog.append(this, "[Unlock] 收到打卡触发")

        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            if (km.isKeyguardSecure) {
                PunchLog.append(this, "[Unlock] 检测到安全锁屏(PIN/图案)，无法自动解锁")
                failUnlock("检测到安全锁屏，无法自动解锁。备用机请不要设置 PIN/图案锁。")
                return
            }
            PunchLog.append(this, "[Unlock] 请求解除非安全锁屏")
            km.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        handler.post { proceed() }
                    }

                    override fun onDismissCancelled() {
                        handler.post { proceed() }
                    }
                }
            )
        } else {
            proceed()
        }
    }

    private fun proceed() {
        WakeUpUtils.holdScreen(applicationContext)
        val test = intent.getBooleanExtra(EXTRA_TEST, false)
        val expected = intent.getLongExtra(PunchScheduler.EXTRA_EXPECTED, 0L)
        val pkg = TargetResolver.resolve(this)
        if (pkg == null) {
            PunchLog.append(this, "[Unlock] 未识别到目标App: ${Prefs.getTargetPackage(this)}")
            failUnlock("未识别到目标App(${Prefs.getTargetPackage(this)})，请确认应用名称或改用包名")
            return
        }
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                PunchLog.append(this, "[Unlock] 未安装目标App: $pkg")
                failUnlock("未安装目标App($pkg)，请先安装并登录")
                return
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launch)
            PunchService.start(this, test = test, expectedMillis = expected)
        }.onFailure { e ->
            PunchLog.append(this, "[Unlock] 启动App失败: ${e.message}")
            failUnlock("启动目标App失败: ${e.message}，请检查是否已登录该App")
        }
        handler.postDelayed({
            runCatching { finish() }
        }, 6_000L)
    }

    private fun failUnlock(reason: String) {
        Prefs.setLastResult(this, reason)
        handler.postDelayed({
            runCatching { finish() }
        }, 1_500L)
    }

    companion object {
        const val EXTRA_TEST = "extra_test"

        fun startForTest(context: Context) {
            val i = Intent(context, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.putExtra(EXTRA_TEST, true)
            context.startActivity(i)
        }
    }
}