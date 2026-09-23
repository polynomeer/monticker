package com.monticker.api.watchrule.infrastructure

import com.monticker.api.watchrule.domain.WatchRule
import org.springframework.data.jpa.repository.JpaRepository

interface WatchRuleRepository : JpaRepository<WatchRule, Long> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<WatchRule>

    /** 컨슈머의 조회 경로 — 부분 인덱스 idx_watch_rules_stock_event 가 받는다. */
    fun findAllByStockIdAndEventTypeAndIsActiveTrue(stockId: Long, eventType: String): List<WatchRule>
}
