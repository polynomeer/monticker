package com.monticker.api.subscription.application

import org.springframework.scheduling.support.CronExpression
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * ADR-083 — 정기결제 스케줄의 단일 정의. 갱신 잡의 실행 시각(`BatchJobScheduler`)과 리더의 선행 기간(`SubscriptionRenewalJobConfig`),
 * 화면의 "다음 결제일"이 모두 이 값을 쓴다 — 셋이 어긋나면 화면이 실제 청구 시각과 다른 날을 약속한다.
 */
object RenewalSchedule {
    /** 갱신 잡 실행 시각 — 매일 01:00 KST(ADR-059). */
    const val CRON = "0 0 1 * * *"
    const val ZONE = "Asia/Seoul"

    /** 리더는 `expiresAt <= 실행 시각 + LOOKAHEAD`인 구독을 청구한다. */
    val LOOKAHEAD: Duration = Duration.ofDays(1)

    private val cron = CronExpression.parse(CRON)
    private val zone = ZoneId.of(ZONE)

    /**
     * 만료가 [expiresAt]인 구독이 청구될 갱신 잡 실행 시각: `expiresAt − LOOKAHEAD` 이후(그리고 [now] 이후)의 첫 실행.
     * 지난 실행이 PG 장애로 보류됐으면(ADR-053) 다음 실행이 다시 시도하므로 [now] 이후로 민다.
     */
    fun nextChargeAt(expiresAt: Instant, now: Instant = Instant.now()): Instant {
        val from = maxOf(expiresAt.minus(LOOKAHEAD), now)
        // CronExpression.next는 "이후"만 준다 — 정각 자체도 실행 시각이므로 1초 앞에서 찾는다.
        return cron.next(from.minusSeconds(1).atZone(zone))!!.toInstant()
    }
}
