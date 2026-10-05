package com.monticker.api.quant.infrastructure

import com.monticker.api.quant.domain.QuantBacktestResult
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface QuantBacktestResultRepository : JpaRepository<QuantBacktestResult, Long> {
    /** 화면은 [0]을 최신 결과로 쓴다 — 정렬 없이 돌려주면 DB 반환 순서에 따라 오래된 결과가 "최신"으로 보였다. */
    fun findAllByRuleSetIdOrderByCreatedAtDescIdDesc(ruleSetId: String): List<QuantBacktestResult>

    /** ADR-078 — 목록 화면용. 룰셋마다 최신 백테스트 1건을 한 번의 쿼리로(카드별 N+1 조회 제거). */
    @Query(
        value = """
            SELECT DISTINCT ON (rule_set_id) *
            FROM quant_backtest_results
            WHERE rule_set_id IN (:ruleSetIds)
            ORDER BY rule_set_id, created_at DESC, id DESC
        """,
        nativeQuery = true,
    )
    fun findLatestByRuleSetIds(@Param("ruleSetIds") ruleSetIds: Collection<String>): List<QuantBacktestResult>
}
