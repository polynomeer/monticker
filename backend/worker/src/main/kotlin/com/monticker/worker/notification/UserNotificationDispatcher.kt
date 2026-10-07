package com.monticker.worker.notification

import com.monticker.worker.push.ExpoPushSender
import com.monticker.worker.push.PushMessage
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component
import java.time.Duration

/** api `UserNotificationCommand`와 같은 모양(ADR-065). api가 Modulith로 외부화한 JSON을 그대로 읽는다. */
data class UserNotificationMessage(
    val userId: Long,
    val title: String,
    val body: String,
    val dedupKey: String,
    val data: Map<String, Any> = emptyMap(),
    /** ADR-082 — 알림 종류(api `NotificationCategory` 이름). 없으면 끌 수 없는 알림으로 본다(이 필드 이전 메시지). */
    val category: String? = null,
)

/**
 * ADR-065 — api가 보낸 사용자 알림의 **발송**: 설정 적용 → 중복 제거 → 푸시(닿지 않으면 이메일).
 *
 * 알림 규칙 발송([com.monticker.worker.alert.AlertDispatcher])과 달리 쿨다운이 아니라 사건 단위 중복 제거다 — 아웃박스
 * 재전송·Kafka 재시도로 같은 명령이 여러 번 와도 한 번만 보낸다. 발송이 예외로 실패하면 표시를 지워 재시도가 다시 보낼 수
 * 있게 한다(그렇지 않으면 재시도가 중복으로 판정돼 알림이 사라진다).
 *
 * ADR-082 — 끌 수 있는 종류는 사용자 알림 설정(전체·채널·종류별)과, 광고성이면 마케팅 동의를 따른다. 끌 수 없는 종류(주문 결과
 * 확인 중·조건부 주문 실패)는 설정을 읽지도 않는다.
 */
@Component
class UserNotificationDispatcher(
    private val jdbc: JdbcTemplate,
    private val pushSender: ExpoPushSender,
    private val redis: StringRedisTemplate,
    private val mailSender: JavaMailSender,
    private val preferences: NotificationPreferenceReader,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun dispatch(msg: UserNotificationMessage) {
        val category = NotificationCategory.fromWire(msg.category)
        // 설정은 중복 표시보다 먼저 읽는다 — 읽기가 실패하면 표시 없이 예외로 끝나 재시도가 다시 판정한다.
        val plan = if (category.alwaysOn) DeliveryPlan.ALWAYS else NotificationPolicy.plan(
            preferences.forUser(msg.userId), category,
            marketingAgreed = category == NotificationCategory.STRATEGY_MARKET && preferences.marketingAgreed(msg.userId),
        )
        if (plan.none) {
            log.info("[UserNotification] 사용자 설정으로 보내지 않음: userId={} category={} dedupKey={}", msg.userId, category, msg.dedupKey)
            return
        }

        val key = "notify:user:sent:${msg.dedupKey}"
        if (redis.opsForValue().setIfAbsent(key, "1", DEDUP_TTL) != true) {
            log.info("[UserNotification] 중복 — 건너뜀: dedupKey={}", msg.dedupKey)
            return
        }
        try {
            var pushed = false
            if (plan.push) {
                val tokens = jdbc.queryForList(
                    "SELECT token FROM device_tokens WHERE user_id = ? AND is_active = true", String::class.java, msg.userId,
                )
                if (tokens.isNotEmpty()) {
                    val results = pushSender.send(tokens.map { PushMessage(to = it, title = msg.title, body = msg.body, data = msg.data) })
                    // 서킷 OPEN이면 빈 결과, 전송 실패면 전부 error다 — 어느 기기에도 닿지 않았으면 이메일로 보낸다.
                    pushed = results.any { it.status == "ok" }
                    if (pushed) log.info("[UserNotification] 푸시 발송: userId={} dedupKey={}", msg.userId, msg.dedupKey)
                    else log.warn("[UserNotification] 푸시 실패 — 이메일로 보낸다: userId={} dedupKey={}", msg.userId, msg.dedupKey)
                }
            }
            if (plan.email || (plan.emailIfPushMissed && !pushed)) sendEmail(msg)
        } catch (e: Exception) {
            redis.delete(key)
            throw e
        }
    }

    private fun sendEmail(msg: UserNotificationMessage) {
        val email = jdbc.queryForList(
            "SELECT email FROM users WHERE id = ? AND deleted_at IS NULL", String::class.java, msg.userId,
        ).firstOrNull() ?: run {
            log.warn("[UserNotification] 수신자 없음(탈퇴?) — 발송하지 않는다: userId={}", msg.userId)
            return
        }
        mailSender.send(SimpleMailMessage().apply {
            setTo(email)
            subject = "[monticker] ${msg.title}"
            text = msg.body
        })
        log.info("[UserNotification] 이메일 발송: userId={} dedupKey={}", msg.userId, msg.dedupKey)
    }

    private companion object {
        val DEDUP_TTL: Duration = Duration.ofDays(2)   // notify.user 보존(1일) + 재시도 여유
    }
}
