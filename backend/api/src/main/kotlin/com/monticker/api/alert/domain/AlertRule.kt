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

    /** ADR-073 — 삭제 시각. 삭제한 규칙은 다시 켤 수 없다(끄기는 isActive=false만). */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null

    /** 끄기(일시 중지) — 다시 켤 수 있다 */
    fun deactivate() {
        isActive = false
        updatedAt = Instant.now()
    }

    /** 삭제 — 꺼지고 다시 켤 수 없다 */
    fun delete() {
        isActive = false
        if (deletedAt == null) deletedAt = Instant.now()
        updatedAt = Instant.now()
    }

    fun activate() {
        check(!isDeleted) { "삭제한 알림 규칙은 다시 켤 수 없습니다" }
        isActive = true
        updatedAt = Instant.now()
    }
}

/**
 * 워커가 평가할 수 있는 규칙인지 — 생성과 다시 켜기가 같은 기준을 쓴다.
 * 워커의 AlertEvaluator가 조건 필드를 못 찾으면 조용히 무시하고 룰이 영영 발동하지 않는다(evaluateRule의
 * `?: return` 패턴) — "저장은 됐는데 평생 안 울리는 룰"이 쌓이지 않게 최소한의 필드 존재만 확인한다.
 * 값 자체(0 이하 등)는 워커 쪽 지표 계산 가드가 처리한다.
 */
object AlertRuleConditions {
    fun isValid(type: AlertRuleType, stockId: Long?, condition: Map<String, Any?>): Boolean {
        fun num(key: String) = condition[key] as? Number
        // 워커(AlertRuleIndex)는 stock_id 기준으로 룰을 색인한다 — stock_id 없는 룰은 어떤 틱에도 매칭되지 않아
        // 평생 안 울린다(backlog §9). V45가 컬럼도 NOT NULL로 만들었다.
        if (stockId == null) return false
        return when (type) {
            AlertRuleType.PRICE_ABOVE, AlertRuleType.PRICE_BELOW -> num("threshold") != null
            AlertRuleType.RSI_BELOW, AlertRuleType.RSI_ABOVE -> num("threshold") != null
            AlertRuleType.PRICE_BELOW_MA, AlertRuleType.PRICE_ABOVE_MA -> true
            AlertRuleType.HOLDING_DROP -> num("dropPct") != null
            AlertRuleType.VOLUME_SURGE -> true
            // 평가기가 없는 타입 — 저장은 되지만 어떤 워커도 보지 않는다.
            AlertRuleType.NEWS_PUBLISHED, AlertRuleType.DISCLOSURE_PUBLISHED -> false
        }
    }
}

enum class AlertRuleType {
    PRICE_ABOVE, PRICE_BELOW, VOLUME_SURGE,
    // NEWS_PUBLISHED / DISCLOSURE_PUBLISHED: 평가기가 구현된 적이 없다 — API가 생성을 거부한다(AlertController).
    // 기존 저장 행의 역직렬화를 위해 enum 값은 남긴다.
    NEWS_PUBLISHED, DISCLOSURE_PUBLISHED,
    // RSI/이동평균은 candles_1d의 완결된 일봉 종가 기준으로 계산한다 — 장중에는 틱마다
    // 같은 값을 재사용하며, 일봉이 갱신되는 다음날부터 새 값을 반영한다(Quant Lab
    // 백테스팅과 동일한 일봉 기준 지표라 사용자 기대와 어긋나지 않음).
    RSI_BELOW, RSI_ABOVE, PRICE_BELOW_MA, PRICE_ABOVE_MA,
    // 모의투자(paper trading) 보유 포지션 한정 — 실브로커리지 보유종목은 DB에 캐시되지
    // 않고(매 요청마다 브로커 API 실시간 호출) symbol↔stockId 매핑도 없어 워커가 틱마다
    // 평가할 방법이 없다. 별도 동기화 인프라가 생기기 전까지는 범위 밖으로 명시적으로 뺀다.
    HOLDING_DROP
}
