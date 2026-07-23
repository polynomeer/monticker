package com.monticker.api.quant.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

data class StrategyShareRequest(val rulesetId: Long, val description: String? = null)

@RestController
@RequestMapping("/api/quant/market")
class StrategyMarketController(
    private val jdbc: JdbcTemplate,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<List<Map<String, Any?>>> {
        val rows = jdbc.queryForList(
            """SELECT sm.id, sm.description, sm.subscribe_count, sm.created_at,
                      qr.name, qr.rule_definition, u.email AS author_email
               FROM strategy_market sm
               JOIN quant_rulesets qr ON qr.id = sm.ruleset_id
               JOIN users u ON u.id = sm.user_id
               WHERE qr.deleted_at IS NULL
               ORDER BY sm.subscribe_count DESC, sm.created_at DESC
               LIMIT ? OFFSET ?""",
            size, page * size,
        )
        return ResponseEntity.ok(rows)
    }

    @PostMapping("/share")
    fun share(
        @RequestHeader("Authorization") auth: String,
        @RequestBody req: StrategyShareRequest,
    ): ResponseEntity<Map<String, Any>> {
        val userId = jwtTokenProvider.getUserId(auth.removePrefix("Bearer ").trim())
        val id = jdbc.queryForObject(
            """INSERT INTO strategy_market (ruleset_id, user_id, description, subscribe_count, created_at)
               VALUES (?, ?, ?, 0, NOW())
               ON CONFLICT (ruleset_id) DO UPDATE SET description = EXCLUDED.description
               RETURNING id""",
            Long::class.java,
            req.rulesetId, userId, req.description,
        ) ?: 0L
        return ResponseEntity.ok(mapOf("id" to id, "rulesetId" to req.rulesetId))
    }

    @PostMapping("/{id}/subscribe")
    fun subscribe(
        @RequestHeader("Authorization") auth: String,
        @PathVariable id: Long,
    ): ResponseEntity<Map<String, Any>> {
        val userId = jwtTokenProvider.getUserId(auth.removePrefix("Bearer ").trim())
        jdbc.update(
            """INSERT INTO strategy_subscriptions (market_id, user_id, created_at)
               VALUES (?, ?, NOW())
               ON CONFLICT DO NOTHING""",
            id, userId,
        )
        jdbc.update("UPDATE strategy_market SET subscribe_count = subscribe_count + 1 WHERE id = ?", id)
        return ResponseEntity.ok(mapOf("subscribed" to true))
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
