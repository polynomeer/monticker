package com.monticker.api.auth.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class EmailService(
    private val mailSender: JavaMailSender,
    @Value("\${app.mail.from:noreply@monticker.io}") private val from: String,
    @Value("\${app.base-url:http://localhost:3000}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    fun sendVerificationEmail(to: String, token: String) {
        val link = "$baseUrl/verify-email?token=$token"
        sendHtml(
            to      = to,
            subject = "[monticker] 이메일 인증을 완료해주세요",
            html    = """
                <h2>monticker에 오신 것을 환영합니다!</h2>
                <p>아래 버튼을 클릭하여 이메일 인증을 완료해주세요. (24시간 유효)</p>
                <a href="$link" style="display:inline-block;padding:12px 24px;background:#5c7cfa;color:#fff;text-decoration:none;border-radius:6px;">이메일 인증하기</a>
                <p style="color:#888;font-size:12px;margin-top:24px;">버튼이 작동하지 않으면 아래 링크를 복사해 브라우저에 붙여넣으세요:<br>$link</p>
            """.trimIndent(),
        )
    }

    @Async
    fun sendPasswordResetEmail(to: String, token: String) {
        val link = "$baseUrl/reset-password?token=$token"
        sendHtml(
            to      = to,
            subject = "[monticker] 비밀번호 재설정 링크",
            html    = """
                <h2>비밀번호 재설정 요청</h2>
                <p>아래 버튼을 클릭하여 새 비밀번호를 설정하세요. (30분 유효)</p>
                <a href="$link" style="display:inline-block;padding:12px 24px;background:#5c7cfa;color:#fff;text-decoration:none;border-radius:6px;">비밀번호 재설정</a>
                <p style="color:#888;font-size:12px;margin-top:24px;">요청하지 않으셨다면 이 이메일을 무시하세요.</p>
            """.trimIndent(),
        )
    }

    private fun sendHtml(to: String, subject: String, html: String) {
        try {
            val msg = mailSender.createMimeMessage()
            MimeMessageHelper(msg, false, "UTF-8").apply {
                setFrom(from)
                setTo(to)
                setSubject(subject)
                setText(html, true)
            }
            mailSender.send(msg)
        } catch (e: Exception) {
            log.warn("[EmailService] 메일 발송 실패 to={}: {}", to, e.message)
        }
    }
}
