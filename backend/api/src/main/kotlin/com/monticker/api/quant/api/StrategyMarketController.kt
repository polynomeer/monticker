package com.monticker.api.quant.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.quant.application.StrategyPerformanceQuery
import com.monticker.api.quant.domain.RuleSetStatus
import com.monticker.api.quant.infrastructure.RuleSetRepository
import com.monticker.api.settlement.creator.application.CreatorEarningsService
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class StrategyShareRequest(
    val rulesetId: String,
    val description: String? = null,
    val price: BigDecimal = BigDecimal.ZERO,
)

private val MAX_PRICE = BigDecimal(1_000_000)

@Validated
@RestController
@RequestMapping("/api/quant/market")
class StrategyMarketController(
    private val jdbc: JdbcTemplate,
    private val jwtTokenProvider: JwtTokenProvider,
    private val creatorEarningsService: CreatorEarningsService,
    private val ruleSetRepository: RuleSetRepository,
    private val performanceQuery: StrategyPerformanceQuery,
) {
    @GetMapping
    fun list(
        // ADR-035 — isSubscribed 계산에 로그인 사용자가 필요하지만, 마켓 둘러보기 자체는
        // 로그인 없이도 가능해야 하므로 필수로 만들지 않는다.
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<List<Map<String, Any?>>> {
        val userId = auth?.let { runCatching { jwtTokenProvider.getUserId(it.removePrefix("Bearer ").trim()) }.getOrNull() }

        val rows = jdbc.queryForList(
            // ADR-035 — price가 빠져 있으면 구매자가 얼마가 청구될지 모른 채 구독을 누르게 된다.
            // 작성자는 닉네임으로만 표시한다 — 이 목록은 비로그인에도 열려 있고, 이메일은 로그인 ID다(보안 리뷰 2026-10).
            """SELECT sm.id, sm.ruleset_id, sm.description, sm.price, sm.subscribe_count, sm.created_at,
                      u.nickname AS author_nickname
               FROM strategy_market sm
               JOIN users u ON u.id = sm.user_id
               ORDER BY sm.subscribe_count DESC, sm.created_at DESC
               LIMIT ? OFFSET ?""",
            size, page * size,
        )

        // ruleset_id는 Postgres FK가 아니라 Mongo(rule_sets)의 ObjectId라 SQL JOIN이 불가능하다 —
        // 전략 이름은 이 별도 조회로 채워 넣는다(빠지면 프론트 카드 제목이 항상 빈 문자열이 된다).
        val rulesetIds = rows.mapNotNull { it["ruleset_id"] as? String }
        val namesById = ruleSetRepository.findAllById(rulesetIds).associate { it.id to it.name }

        // ADR-078 — 카드 성과(최신 백테스트 지표·다운샘플 곡선·포워드 일치율). 룰 정의는 싣지 않는다(ADR-035).
        val performance = performanceQuery.summarize(rulesetIds)

        val subscribedMarketIds: Set<Long> = if (userId != null) {
            jdbc.queryForList("SELECT market_id FROM strategy_subscriptions WHERE user_id = ?", Long::class.java, userId).toSet()
        } else emptySet()

        val enriched = rows.map { row ->
            LinkedHashMap(row).apply {
                put("name", namesById[row["ruleset_id"]] ?: "(삭제된 전략)")
                put("isSubscribed", (row["id"] as Number).toLong() in subscribedMarketIds)
                put("performance", performance[row["ruleset_id"]])
            }
        }
        return ResponseEntity.ok(enriched)
    }

    @PostMapping("/share")
    fun share(
        @RequestHeader("Authorization") auth: String,
        @RequestBody req: StrategyShareRequest,
    ): ResponseEntity<*> {
        val userId = jwtTokenProvider.getUserId(auth.removePrefix("Bearer ").trim())

        // 음수·소수·과도한 가격이 그대로 저장되면 구독 결제 금액이 그 값이 된다.
        require(req.price >= BigDecimal.ZERO && req.price <= MAX_PRICE && req.price.stripTrailingZeros().scale() <= 0) {
            "월 구독료는 0원 이상 ${MAX_PRICE.toPlainString()}원 이하의 정수여야 합니다."
        }

        // req.rulesetId를 그대로 믿고 INSERT하면 남의 룰셋 ID를 알아내는 것만으로 그 룰셋을
        // 마켓에 공유해버릴 수 있었다(broken object-level authorization) — 소유권을 먼저 확인한다.
        val doc = ruleSetRepository.findByIdAndUserId(req.rulesetId, userId)
            .orElseThrow { NoSuchElementException("룰셋을 찾을 수 없습니다: ${req.rulesetId}") }
        // 메시지가 GlobalExceptionHandler의 IllegalStateException 비즈니스 규칙 키워드에
        // 안 걸려도 항상 400으로 처리되도록 IllegalArgumentException을 쓴다.
        require(doc.status in setOf(RuleSetStatus.BACKTESTED.name, RuleSetStatus.RUNNING.name)) {
            "백테스트를 먼저 완료해야 공유할 수 있습니다."
        }

        val id = jdbc.queryForObject(
            """INSERT INTO strategy_market (ruleset_id, user_id, description, price, subscribe_count, created_at)
               VALUES (?, ?, ?, ?, 0, NOW())
               ON CONFLICT (ruleset_id) DO UPDATE
                   SET description = EXCLUDED.description, price = EXCLUDED.price
               RETURNING id""",
            Long::class.java,
            req.rulesetId, userId, req.description, req.price,
        ) ?: 0L
        return ResponseEntity.ok(mapOf("id" to id, "rulesetId" to req.rulesetId, "price" to req.price))
    }

    // ADR-035 — @Transactional 없이는 결제(onStrategySubscribed)가 실패해도 이미 INSERT된
    // 구독 행이 커밋된 채로 남는다(무료로 접근권만 얻는 정합성 버그). 실패 시 전체 롤백되도록 묶는다.
    @PostMapping("/{id}/subscribe")
    @Transactional
    fun subscribe(
        @RequestHeader("Authorization") auth: String,
        @PathVariable id: Long,
    ): ResponseEntity<Map<String, Any>> {
        val userId = jwtTokenProvider.getUserId(auth.removePrefix("Bearer ").trim())

        // 전략 정보 조회 (creator, price)
        val strategy = jdbc.queryForMap(
            "SELECT user_id AS creator_id, price, ruleset_id FROM strategy_market WHERE id = ?", id
        )
        val creatorId  = (strategy["creator_id"] as Number).toLong()
        val price      = (strategy["price"] as? java.math.BigDecimal) ?: BigDecimal.ZERO
        val rulesetId  = strategy["ruleset_id"] as String

        require(creatorId != userId) { "자신의 전략을 구독할 수 없습니다." }

        // ADR-080 — 유료 전략 구독은 결제 흐름(사전 주문·PG 확정·갱신·환불)과 법무 판단(유사투자자문업,
        // 정산 원천징수)이 정해지기 전까지 서버에서 닫는다. 화면이 막아 두어도 API는 직접 호출할 수 있었고,
        // Mock PG 환경에서는 실제 결제 없이 제작자 수익이 적립됐다.
        if (price > BigDecimal.ZERO) {
            throw BusinessRuleException("유료 전략 구독 결제는 아직 열리지 않았습니다. 무료 전략만 구독할 수 있습니다.")
        }

        val inserted = jdbc.update(
            """INSERT INTO strategy_subscriptions (market_id, user_id, created_at)
               VALUES (?, ?, NOW())
               ON CONFLICT DO NOTHING""",
            id, userId,
        )

        if (inserted > 0) {
            jdbc.update("UPDATE strategy_market SET subscribe_count = subscribe_count + 1 WHERE id = ?", id)
            // 수익 분배 — price > 0이면 PG 결제 후 creator_earnings 적립
            creatorEarningsService.onStrategySubscribed(
                strategyId   = id,
                creatorId    = creatorId,
                subscriberId = userId,
                price        = price,
                strategyCode = rulesetId,
            )
        }

        return ResponseEntity.ok(mapOf("subscribed" to (inserted > 0)))
    }

    @DeleteMapping("/{id}/subscribe")
    fun unsubscribe(
        @RequestHeader("Authorization") auth: String,
        @PathVariable id: Long,
    ): ResponseEntity<Map<String, Any>> {
        val userId = jwtTokenProvider.getUserId(auth.removePrefix("Bearer ").trim())
        val deleted = jdbc.update(
            "DELETE FROM strategy_subscriptions WHERE market_id = ? AND user_id = ?",
            id, userId,
        )
        if (deleted > 0) {
            jdbc.update("UPDATE strategy_market SET subscribe_count = GREATEST(subscribe_count - 1, 0) WHERE id = ?", id)
        }
        return ResponseEntity.ok(mapOf("unsubscribed" to true))
    }
}
