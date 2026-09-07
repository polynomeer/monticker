package com.monticker.api.quant.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.quant.infrastructure.RuleSetRepository
import com.monticker.api.settlement.creator.application.CreatorEarningsService
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class StrategyShareRequest(
    val rulesetId: String,
    val description: String? = null,
    val price: BigDecimal = BigDecimal.ZERO,
)

@Validated
@RestController
@RequestMapping("/api/quant/market")
class StrategyMarketController(
    private val jdbc: JdbcTemplate,
    private val jwtTokenProvider: JwtTokenProvider,
    private val creatorEarningsService: CreatorEarningsService,
    private val ruleSetRepository: RuleSetRepository,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<List<Map<String, Any?>>> {
        val rows = jdbc.queryForList(
            """SELECT sm.id, sm.ruleset_id, sm.description, sm.subscribe_count, sm.created_at,
                      u.email AS author_email
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

        val enriched = rows.map { row ->
            LinkedHashMap(row).apply { put("name", namesById[row["ruleset_id"]] ?: "(삭제된 전략)") }
        }
        return ResponseEntity.ok(enriched)
    }

    @PostMapping("/share")
    fun share(
        @RequestHeader("Authorization") auth: String,
        @RequestBody req: StrategyShareRequest,
    ): ResponseEntity<Map<String, Any>> {
        val userId = jwtTokenProvider.getUserId(auth.removePrefix("Bearer ").trim())
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

    @PostMapping("/{id}/subscribe")
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
