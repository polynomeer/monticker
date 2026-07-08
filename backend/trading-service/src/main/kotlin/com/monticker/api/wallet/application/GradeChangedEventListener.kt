package com.monticker.api.wallet.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.wallet.domain.BehaviorGrade
import com.monticker.api.wallet.events.GradeChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 행동 등급 변경 이벤트 수신 → 사용자 푸시 알림 발송.
 *
 * @TransactionalEventListener(AFTER_COMMIT): BehaviorScore 저장 트랜잭션 커밋 후에만 실행.
 * @Async: 푸시 발송이 Batch 스레드를 블로킹하지 않도록 alertDispatchExecutor에서 처리.
 */
@Component
class GradeChangedEventListener(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send"

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("behaviorScoreExecutor")
    fun onGradeChanged(event: GradeChangedEvent) {
        val tokens = jdbc.queryForList(
            "SELECT token FROM device_tokens WHERE user_id = ? AND is_active = true",
            String::class.java,
            event.userId,
        )
        if (tokens.isEmpty()) {
            log.debug("[GradeChanged] userId={} — device token 없음, 푸시 건너뜀", event.userId)
            return
        }

        val (title, body) = buildMessage(event)
        log.info(
            "[GradeChanged] userId={} {}→{} score={} tokens={}",
            event.userId, event.previousGrade, event.newGrade, event.behaviorScore, tokens.size,
        )

        sendPush(tokens, title, body, event)
    }

    private fun buildMessage(event: GradeChangedEvent): Pair<String, String> {
        val arrow = if (isUpgrade(event.previousGrade, event.newGrade)) "↑" else "↓"
        val title = "투자 행동 등급 변경 $arrow"
        val body = buildString {
            event.previousGrade?.let { append("${it.label} → ") }
            append("${event.newGrade.label} (점수: ${event.behaviorScore}점)")
            if (isUpgrade(event.previousGrade, event.newGrade)) {
                append("\n투자 습관이 개선되고 있습니다!")
            } else {
                append("\n투자 패턴을 점검해보세요.")
            }
        }
        return title to body
    }

    private fun isUpgrade(prev: BehaviorGrade?, next: BehaviorGrade): Boolean =
        prev == null || next.ordinal > prev.ordinal

    private fun sendPush(tokens: List<String>, title: String, body: String, event: GradeChangedEvent) {
        runCatching {
            val messages = tokens.map { token ->
                mapOf(
                    "to"       to token,
                    "title"    to title,
                    "body"     to body,
                    "sound"    to "default",
                    "priority" to "normal",
                    "data"     to mapOf(
                        "type"          to "GRADE_CHANGED",
                        "userId"        to event.userId,
                        "newGrade"      to event.newGrade.name,
                        "behaviorScore" to event.behaviorScore,
                        "scoreDate"     to event.scoreDate.toString(),
                    ),
                )
            }
            val payload = objectMapper.writeValueAsString(messages)
            val request = HttpRequest.newBuilder()
                .uri(URI.create(EXPO_PUSH_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            log.info("[GradeChanged] push sent: userId={} status={}", event.userId, response.statusCode())
        }.onFailure {
            log.warn("[GradeChanged] push 발송 실패: userId={} error={}", event.userId, it.message)
        }
    }
}
