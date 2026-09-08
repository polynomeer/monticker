package com.monticker.api.brokerage.application

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.brokerage.domain.RebalanceTarget
import com.monticker.api.brokerage.domain.RebalanceTargetSource
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.RebalanceTargetRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * ADR-034 — 목표 비중 저장/조회. 실행(브로커 호출)은 RebalanceExecutionService가 별도로
 * 담당한다 — ConditionalOrderService/Evaluator의 등록-실행 분리 패턴을 그대로 따른다.
 */
@Service
class RebalanceTargetService(
    private val accountRepo: BrokerageAccountRepository,
    private val targetRepo: RebalanceTargetRepository,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun save(userId: Long, weights: Map<String, BigDecimal>, thresholdPct: BigDecimal, source: RebalanceTargetSource): RebalanceTarget {
        require(weights.isNotEmpty()) { "목표 비중이 비어 있습니다." }
        val total = weights.values.fold(BigDecimal.ZERO, BigDecimal::add)
        require(total <= BigDecimal.ONE) { "목표 비중의 합은 100%를 넘을 수 없습니다 (현재 ${total.multiply(BigDecimal(100))}%)." }
        weights.forEach { (symbol, weight) ->
            require(weight > BigDecimal.ZERO) { "비중은 0보다 커야 합니다: $symbol" }
            resolveStockId(symbol) ?: throw IllegalArgumentException("존재하지 않는 종목입니다: $symbol")
        }
        require(thresholdPct > BigDecimal.ZERO) { "임계값은 0보다 커야 합니다." }

        val account = accountRepo.findByUserIdAndIsActiveTrue(userId)
            .orElseThrow { IllegalStateException("연동된 증권사 계좌가 없습니다.") }

        val existing = targetRepo.findByAccountId(account.id)
        val target = existing.map {
            it.weightsJson = objectMapper.writeValueAsString(weights)
            it.thresholdPct = thresholdPct
            it.source = source
            it.updatedAt = Instant.now()
            it
        }.orElseGet {
            RebalanceTarget(
                userId = userId, accountId = account.id,
                weightsJson = objectMapper.writeValueAsString(weights),
                thresholdPct = thresholdPct, source = source,
            )
        }

        val saved = targetRepo.save(target)
        log.info("리밸런싱 목표 저장: userId={} accountId={} symbols={}", userId, account.id, weights.keys)
        return saved
    }

    @Transactional(readOnly = true)
    fun get(userId: Long): RebalanceTarget? {
        val account = accountRepo.findByUserIdAndIsActiveTrue(userId).orElse(null) ?: return null
        return targetRepo.findByAccountId(account.id).orElse(null)
    }

    fun parseWeights(target: RebalanceTarget): Map<String, BigDecimal> =
        objectMapper.readValue(target.weightsJson, object : TypeReference<Map<String, BigDecimal>>() {})

    private fun resolveStockId(symbol: String): Long? =
        runCatching {
            jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, symbol)
        }.getOrNull()
}
