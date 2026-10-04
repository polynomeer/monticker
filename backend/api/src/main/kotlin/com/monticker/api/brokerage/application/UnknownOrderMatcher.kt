package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.infrastructure.BrokerOrderSnapshot
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * ADR-056 — 결과 불명 주문을 증권사 당일 주문 목록의 한 건과 짝짓는 규칙. 순수 함수다(테스트·추론이 쉽도록).
 *
 * 두 증권사 모두 우리가 만든 식별자로 주문을 조회할 수 없어 (종목, 방향, 수량, 가격, 주문시각 창)으로 매칭한다.
 * 이미 우리의 다른 주문에 연결된 증권사 주문번호는 후보에서 뺀다.
 */
object UnknownOrderMatcher {

    /** 증권사 주문시각이 우리 의도 기록보다 앞설 수 있는 여유(시계 차이). KIS 주문시각은 초 단위다. */
    val WINDOW_BEFORE: Duration = Duration.ofSeconds(5)
    /** 의도 기록 후 이 안에 접수된 주문만 후보다(브로커 읽기 타임아웃 5s에 넉넉한 여유). */
    val WINDOW_AFTER: Duration = Duration.ofSeconds(60)
    /** 후보가 0건이어도 이 시간 전에는 "미접수"로 단정하지 않는다 — 증권사 목록 반영 지연. */
    val NOT_FOUND_GRACE: Duration = Duration.ofMinutes(2)

    data class Intent(
        val symbol: String,
        val side: String,
        val quantity: Int,
        val limitPrice: BigDecimal?,   // null이면 시장가 — 가격을 비교하지 않는다
        val recordedAt: Instant,
    )

    sealed interface Decision {
        /** 증권사 조회 자체가 실패 — 아무것도 단정하지 않는다. */
        data object LookupFailed : Decision
        /** 후보가 없지만 아직 목록 반영 지연일 수 있다. */
        data object Wait : Decision
        /** 유예가 지나도 후보가 없다 — 증권사에 접수되지 않았다. */
        data object NotFound : Decision
        /** 후보가 2건 이상 — 자동으로 고르지 않는다. */
        data class Ambiguous(val candidates: Int) : Decision
        data class Matched(val snapshot: BrokerOrderSnapshot) : Decision
    }

    fun decide(intent: Intent, snapshots: List<BrokerOrderSnapshot>?, knownBrokerIds: Set<String>, now: Instant): Decision {
        snapshots ?: return Decision.LookupFailed
        val from = intent.recordedAt.minus(WINDOW_BEFORE)
        val to = intent.recordedAt.plus(WINDOW_AFTER)
        val candidates = snapshots.filter { s ->
            s.brokerOrderId !in knownBrokerIds &&
                s.symbol == intent.symbol &&
                s.side == intent.side &&
                s.quantity == intent.quantity &&
                !s.orderedAt.isBefore(from) && !s.orderedAt.isAfter(to) &&
                (intent.limitPrice == null || (s.price != null && s.price.compareTo(intent.limitPrice) == 0))
        }
        return when {
            candidates.size == 1 -> Decision.Matched(candidates.single())
            candidates.size > 1 -> Decision.Ambiguous(candidates.size)
            Duration.between(intent.recordedAt, now) >= NOT_FOUND_GRACE -> Decision.NotFound
            else -> Decision.Wait
        }
    }
}
