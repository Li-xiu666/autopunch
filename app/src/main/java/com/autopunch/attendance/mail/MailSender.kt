package com.autopunch.attendance.mail

import com.autopunch.attendance.config.Prefs
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import android.content.Context

object MailSender {

    private const val HOST = "smtp.qq.com"
    private const val PORT = "465"

    fun isConfigured(context: Context): Boolean =
        Prefs.getSmtpEmail(context).isNotEmpty() &&
            Prefs.getSmtpCode(context).isNotEmpty() &&
            Prefs.getToEmail(context).isNotEmpty()

    fun send(
        context: Context,
        subject: String,
        body: String
    ): String {
        if (!isConfigured(context)) return "未配置邮箱"

        val email = Prefs.getSmtpEmail(context)
        val code = Prefs.getSmtpCode(context)
        val to = Prefs.getToEmail(context)

        val props = Properties().apply {
            put("mail.smtp.host", HOST)
            put("mail.smtp.port", PORT)
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.writetimeout", "15000")
        }

        return runCatching {
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(email, code)
            })
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(email))
                setRecipients(Message.RecipientType.TO, InternetAddress(to).toString())
                this.subject = subject
                setText(body, "utf-8")
            }
            Transport.send(msg)
            "ok"
        }.getOrElse { e ->
            val root = generateSequence(e as? Throwable) { it.cause }.lastOrNull()
            "发送失败: ${root?.message ?: e.message}"
        }
    }
}