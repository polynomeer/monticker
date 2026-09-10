package com.monticker.api.alert.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "alert_rules")
class AlertRule(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "stock_id")
    val stockId: Long? = null,

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    val ruleType: AlertRuleType,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    val conditionJson: String,

    @Column(nullable = false)
    var isActive: Boolean = true,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun deactivate() {
        isActive = false
        updatedAt = Instant.now()
    }

    fun activate() {
        isActive = true
        updatedAt = Instant.now()
    }
}

enum class AlertRuleType {
    PRICE_ABOVE, PRICE_BELOW, VOLUME_SURGE, NEWS_PUBLISHED, DISCLOSURE_PUBLISHED,
    // RSI/이동평균은 candles_1d의 완결된 일봉 종가 기준으로 계산한다 — 장중에는 틱마다
    // 같은 값을 재사용하며, 일봉이 갱신되는 다음날부터 새 값을 반영한다(Quant Lab
    // 백테스팅과 동일한 일봉 기준 지표라 사용자 기대와 어긋나지 않음).
    RSI_BELOW, RSI_ABOVE, PRICE_BELOW_MA, PRICE_ABOVE_MA,
    // 모의투자(paper trading) 보유 포지션 한정 — 실브로커리지 보유종목은 DB에 캐시되지
    // 않고(매 요청마다 브로커 API 실시간 호출) symbol↔stockId 매핑도 없어 워커가 틱마다
    // 평가할 방법이 없다. 별도 동기화 인프라가 생기기 전까지는 범위 밖으로 명시적으로 뺀다.
    HOLDING_DROP
}
