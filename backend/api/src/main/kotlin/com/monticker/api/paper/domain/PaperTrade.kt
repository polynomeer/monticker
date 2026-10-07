package com.monticker.api.paper.domain
import jakarta.persistence.*
import java.math.BigDecimal; import java.time.Instant

@Entity @Table(name = "paper_trades")
class PaperTrade(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Column(name = "stock_id", nullable = false) val stockId: Long,
    @Column(nullable = false) val side: String,
    @Column(nullable = false) val quantity: Int,
    @Column(nullable = false) val price: BigDecimal,
    @Column(nullable = false) val amount: BigDecimal,
    @Column(name = "traded_at", nullable = false) val tradedAt: Instant = Instant.now(),
    /** ADR-047 — 매칭 엔진 체결 링크. 구 페이퍼 경로(ADR-047 이전)의 거래는 null. */
    @Column(name = "fill_id") val fillId: Long? = null,
    /**
     * ADR-085 — 진입 출처(MANUAL · WATCH_RULE · CONDITIONAL · STRATEGY). 체결을 만든 주문의 출처를 그대로 옮긴다.
     * V81 이전 거래는 V82가 백필했고, 판정할 수 없던 거래는 null(화면 "—").
     */
    @Column(name = "origin", length = 20) val origin: String? = null,
    /** ADR-085 — 출처 ref(watch_rules.id · paper_conditional_orders.id · 룰셋 id). MANUAL이면 null. */
    @Column(name = "origin_ref") val originRef: Long? = null,
)
