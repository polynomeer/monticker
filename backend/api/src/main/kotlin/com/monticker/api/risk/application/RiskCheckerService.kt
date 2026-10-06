package com.monticker.api.risk.application

import com.fasterxml.jackson.databind.ObjectMapper
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
        dryRun: Boolean = false,
    ) {
        jdbc.update(
            """INSERT INTO risk_check_logs (user_id, stock_id, side, quantity, approved, blocked_by, checks_json, account_type, dry_run)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
            userId, stockId, side, qty, approved, blockedBy,
            objectMapper.writeValueAsString(checks), accountType, dryRun,
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
    private val limitService: RiskLimitService,
    private val riskRuleQueryService: RiskRuleQueryService,
    private val auditLogger: RiskCheckAuditLogger,
    private val registry: io.micrometer.core.instrument.MeterRegistry,
    private val jdbc: JdbcTemplate,
) {

    /** 모의계좌 오늘(KST) 실현 손익 — 일간 손실 규칙이 판정에 쓰는 바로 그 값. 리스크 화면 표시용. */
    @Transactional(readOnly = true)
    fun paperRealizedPnlToday(userId: Long): java.math.BigDecimal = riskRuleQueryService.paperRealizedPnlToday(userId)

    /**
     * 존재하지 않는 종목의 주문은 리스크 판정 이전에 404로 막는다. 이 검사가 없으면 risk_check_logs 의
     * stock_id FK 위반이 500으로 새어 나갔다(L-05 §4.2 발견). 종목 존재는 risk 도메인의 관심사가 아니지만,
     * 없는 종목을 risk-check 할 수는 없다 — audit 로그 INSERT(FK) 전에 여기가 유일한 공통 길목이다.
     */
    /**
     * side는 감사 행과 메트릭 라벨(risk_check_total{side})이 된다. 자유 문자열을 받으면 시계열이 무한히 늘고 감사 기록이 오염된다
     * (보안 리뷰 2026-10). 감사 INSERT·메트릭보다 먼저 거부한다 — 대소문자도 정규화하지 않는다(주문 경로는 항상 대문자다).
     */
    private fun requireSide(side: String) {
        require(side in SIDES) { "side는 BUY 또는 SELL이어야 합니다." }
    }

    private fun ensureStockExists(stockId: Long) {
        val exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM stocks WHERE id = ?)", Boolean::class.java, stockId) ?: false
        if (!exists) throw NoSuchElementException("종목을 찾을 수 없습니다: $stockId")
    }

    /**
     * 페이퍼 경로. `estimatedPrice <= 0`은 "호출자가 가격을 모른다"는 뜻이다 — MARKET 주문은 지정가가 없어
     * RiskCheckedAspect(파라미터에 가격이 없는 MatchingService.submitMarket)와 MatchingController
     * (`limitPrice ?: ZERO`)가 ZERO를 넘긴다. V-H3 이후 RiskRuleQueryService가 추정가 불명을 보수적으로
     * 거부하게 되면서 모든 시장가 매수가 ConcentrationRule로 막혔다(5c53b2b 회귀). 여기서 최근가를
     * 채워 넣고, 그것도 없을 때만 불명으로 남겨 규칙이 거부하게 한다 — "모르는 가격으로 승인"은 그대로 금지.
     */
    fun check(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        estimatedPrice: BigDecimal,
    ): RiskCheckResult = paperCheck(userId, stockId, side, qty, estimatedPrice, dryRun = false)

    /**
     * 리스크 설정 화면의 사전 점검(POST /api/risk/check) — 판정은 [check]와 같지만 주문이 아니다. 감사 기록에 `dry_run`으로
     * 남겨 차단 기록·이번 달 차단 집계(RiskDecisionQueryService)에서 빼고, 거부율 메트릭(risk_check_total)에도 넣지 않는다.
     */
    fun dryRun(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        estimatedPrice: BigDecimal,
    ): RiskCheckResult = paperCheck(userId, stockId, side, qty, estimatedPrice, dryRun = true)

    private fun paperCheck(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        estimatedPrice: BigDecimal,
        dryRun: Boolean,
    ): RiskCheckResult {
        requireSide(side)
        ensureStockExists(stockId)
        val limits = limitService.effective(userId)
        // ADR-069 — 리스크 체크를 끈 모의계좌는 한도 규칙을 평가하지 않는다. 수량 검증은 사용자 선호가 아니라 입력 검증이라 남긴다.
        // 판정과 감사 기록은 그대로 남긴다("꺼져 있어서 통과했다"는 사실도 기록이다).
        if (!limits.isActive) {
            val checks = riskRuleQueryService.quantityGuard(qty) + RuleResult(
                rule = "RiskChecksDisabled", passed = true, detail = "리스크 체크가 꺼져 있어 한도 규칙을 평가하지 않았습니다(모의투자).",
                current = 0.0, limit = 0.0,
            )
            return finalize(userId, stockId, side, qty, checks, accountType = "PAPER", dryRun = dryRun)
        }
        val price = if (estimatedPrice > BigDecimal.ZERO) estimatedPrice else riskRuleQueryService.currentPrice(stockId)
        val checks = riskRuleQueryService.evaluate(userId, stockId, side, qty, price, limits)
        return finalize(userId, stockId, side, qty, checks, accountType = "PAPER", dryRun = dryRun)
    }

    /**
     * ADR-074 Note — 미체결 모의 지정가 매수의 체결 직전 재판정(matching.LimitOrderFiller). 제출 시점 판정 이후 다른 매수·체결·손실로
     * 상태가 바뀌었을 수 있으므로 잔량을 체결가로 다시 본다. 주문 자신은 대기 매수에서 빼서 이중으로 세지 않는다.
     * 리스크 체크를 끈 계좌는 [check]와 같이 수량 검증만 한다. 감사 기록은 남긴다(체결 시점 차단도 차단이다).
     */
    fun checkPaperFill(
        userId: Long,
        stockId: Long,
        qty: Int,
        fillPrice: BigDecimal,
        orderId: Long,
    ): RiskCheckResult {
        val limits = limitService.effective(userId)
        val checks = if (!limits.isActive) {
            riskRuleQueryService.quantityGuard(qty) + RuleResult(
                rule = "RiskChecksDisabled", passed = true, detail = "리스크 체크가 꺼져 있어 한도 규칙을 평가하지 않았습니다(모의투자).",
                current = 0.0, limit = 0.0,
            )
        } else {
            riskRuleQueryService.evaluatePaperFill(userId, orderId, stockId, qty, fillPrice, limits)
        }
        return finalize(userId, stockId, "BUY", qty, checks, accountType = "PAPER")
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
        requireSide(side)
        ensureStockExists(stockId)
        // ADR-069 — 실거래는 isActive를 보지 않는다. 리스크 체크 끄기는 모의투자에만 적용되고, 실거래 게이트는 항상 돈다.
        // 한도 값은 같은 유효 한도(완화 24시간 지연 포함)를 쓴다.
        val limits = limitService.effective(userId)
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
        dryRun: Boolean = false,
    ): RiskCheckResult {
        val blockedBy = checks.firstOrNull { !it.passed }?.rule
        val approved  = blockedBy == null
        val severity  = when {
            !approved              -> "BLOCKED"
            checks.any { !it.passed } -> "WARNING"
            else                   -> "APPROVED"
        }

        auditLogger.record(userId, stockId, side, qty, approved, blockedBy, checks, accountType, dryRun)
        if (dryRun) return RiskCheckResult(approved = approved, blockedBy = blockedBy, severity = severity, checks = checks)
        // Trading 대시보드 "리스크 거부율" — 감사 로그는 DB에만 있어 추이를 볼 수 없었다. 룰 라벨은 규칙 수(7개)로 유계.
        registry.counter("risk_check_total", "account", accountType, "side", side,
            "result", if (approved) "approved" else "blocked", "rule", blockedBy ?: "none").increment()

        return RiskCheckResult(approved = approved, blockedBy = blockedBy, severity = severity, checks = checks)
    }

    private companion object {
        val SIDES = setOf("BUY", "SELL")
    }
}
