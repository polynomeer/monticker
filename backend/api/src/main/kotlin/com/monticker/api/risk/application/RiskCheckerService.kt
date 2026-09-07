package com.monticker.api.risk.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.risk.domain.RiskLimit
import com.monticker.api.risk.infrastructure.RiskLimitRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 리스크 판정 로그는 호출자의 트랜잭션이 실패해도 (특히 실거래 주문이 리스크 게이트에
 * 막혀 BrokerageService.submitOrder()가 예외를 던지고 롤백되는 경우) 감사 기록으로
 * 남아야 한다 — REQUIRES_NEW로 별도 트랜잭션에 커밋한다.
 */
@Service
class RiskCheckAuditLogger(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        approved: Boolean,
        blockedBy: String?,
        checks: List<RuleResult>,
        accountType: String,
    ) {
        jdbc.update(
            """INSERT INTO risk_check_logs (user_id, stock_id, side, quantity, approved, blocked_by, checks_json, account_type)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
            userId, stockId, side, qty, approved, blockedBy,
            objectMapper.writeValueAsString(checks), accountType,
        )
    }
}

data class RuleResult(
    val rule: String,
    val passed: Boolean,
    val detail: String,
    val current: Double,
    val limit: Double,
)

@NamedInterface("api")
data class RiskCheckResult(
    val approved: Boolean,
    val blockedBy: String?,
    val severity: String,
    val checks: List<RuleResult>,
)

/** brokerage(실거래)와 matching.api.RiskController(페이퍼 설정 화면) 양쪽에서 참조하는 공개 API. */
@NamedInterface("api")
@Service
@Transactional
class RiskCheckerService(
    private val riskLimitRepo: RiskLimitRepository,
    private val riskRuleQueryService: RiskRuleQueryService,
    private val auditLogger: RiskCheckAuditLogger,
) {
    fun check(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        estimatedPrice: BigDecimal,
    ): RiskCheckResult {
        val limits = riskLimitRepo.findByUserId(userId).orElseGet { RiskLimit(userId = userId) }
        val checks = riskRuleQueryService.evaluate(userId, stockId, side, qty, estimatedPrice, limits)
        return finalize(userId, stockId, side, qty, checks, accountType = "PAPER")
    }

    /**
     * ADR-025 — 실거래(BYOK) 주문용. 페이퍼와 동일한 5개 룰을 쓰되, 포트폴리오 상태는
     * 호출자(BrokerageService)가 브로커 API로 직접 조회해 넘긴다 — paper_accounts/
     * paper_trades를 실거래 판정에 잘못 쓰는 사고를 피하기 위해서다.
     */
    fun checkBrokerageOrder(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        estimatedPrice: BigDecimal,
        snapshot: PortfolioSnapshot,
    ): RiskCheckResult {
        val limits = riskLimitRepo.findByUserId(userId).orElseGet { RiskLimit(userId = userId) }
        val checks = riskRuleQueryService.evaluateWithSnapshot(stockId, side, qty, estimatedPrice, limits, snapshot)
        return finalize(userId, stockId, side, qty, checks, accountType = "REAL")
    }

    private fun finalize(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        checks: List<RuleResult>,
        accountType: String,
    ): RiskCheckResult {
        val blockedBy = checks.firstOrNull { !it.passed }?.rule
        val approved  = blockedBy == null
        val severity  = when {
            !approved              -> "BLOCKED"
            checks.any { !it.passed } -> "WARNING"
            else                   -> "APPROVED"
        }

        auditLogger.record(userId, stockId, side, qty, approved, blockedBy, checks, accountType)

        return RiskCheckResult(approved = approved, blockedBy = blockedBy, severity = severity, checks = checks)
    }
}
