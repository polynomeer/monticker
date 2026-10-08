package com.monticker.api.quant.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 마켓 목록·총수·검색이 같은 행 집합을 보도록 FROM 절을 한곳에 둔다. 목록에 없는 행(작성자 계정이
 * 사라진 전략 등)이 총수나 검색에만 잡히거나, 나중에 비공개·숨김 조건이 생겼을 때 한쪽만 고쳐지는 걸 막는다.
 * 여기에 WHERE를 붙여도 검색은 이 조각을 CTE 안에 감싸 쓰므로 그대로 동작한다.
 */
const val MARKET_VISIBLE_FROM = """FROM strategy_market sm
               JOIN users u ON u.id = sm.user_id"""

/**
 * 마켓 카드 한 행의 컬럼. 작성자는 닉네임으로만 싣는다 — 이메일은 로그인 ID다(보안 리뷰 2026-10).
 * price가 빠지면 구매자가 얼마가 청구될지 모른 채 구독을 누르게 된다(ADR-035).
 */
const val MARKET_ROW_COLUMNS =
    "sm.id, sm.ruleset_id, sm.description, sm.price, sm.subscribe_count, sm.created_at, u.nickname AS author_nickname"

/**
 * 전략 마켓 서버 측 검색. 설명·작성자 닉네임은 SQL에서 ILIKE로, 전략 이름은 Mongo(rule_sets)에 있어
 * 호출자가 이름이 맞은 ruleset_id 목록을 넘겨준다. 공개 범위는 [MARKET_VISIBLE_FROM]을 CTE로 감싸 목록과 같다.
 * 사용자 입력은 모두 바인딩하고, SQL 문자열에 들어가는 것은 이 파일의 상수와 `?` 자리표시뿐이다.
 */
@Repository
class StrategyMarketSearchRepository(private val jdbc: JdbcTemplate) {

    companion object {
        /** 이름 매칭을 위해 Mongo에서 이름을 읽어 올 공개 전략 수 상한(구독 많은 순) */
        const val MAX_NAME_SCAN = 2000

        private const val VISIBLE_CTE = "WITH visible AS (SELECT $MARKET_ROW_COLUMNS $MARKET_VISIBLE_FROM)"
        private const val ORDER = "ORDER BY subscribe_count DESC, created_at DESC, id DESC"

        /** ILIKE 부분 일치 패턴 — `\`, `%`, `_`를 이스케이프해 검색어가 와일드카드로 해석되지 않게 한다(ESCAPE '\'). */
        fun likePattern(term: String): String =
            "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }

    /** 공개 전략의 ruleset_id — 이름 검색 후보(구독 많은 순, 최대 [limit]개) */
    fun visibleRulesetIds(limit: Int = MAX_NAME_SCAN): List<String> =
        jdbc.queryForList("$VISIBLE_CTE SELECT ruleset_id FROM visible $ORDER LIMIT ?", String::class.java, limit)

    private fun where(term: String, nameMatchedRulesetIds: Collection<String>): Pair<String, List<Any>> {
        val pattern = likePattern(term)
        val args = mutableListOf<Any>(pattern, pattern)
        val sb = StringBuilder("""WHERE (description ILIKE ? ESCAPE '\' OR author_nickname ILIKE ? ESCAPE '\'""")
        if (nameMatchedRulesetIds.isNotEmpty()) {
            sb.append(" OR ruleset_id IN (${nameMatchedRulesetIds.joinToString(",") { "?" }})")
            args.addAll(nameMatchedRulesetIds)
        }
        sb.append(")")
        return sb.toString() to args
    }

    fun search(term: String, nameMatchedRulesetIds: Collection<String>, limit: Int, offset: Int): List<Map<String, Any?>> {
        val (where, args) = where(term, nameMatchedRulesetIds)
        return jdbc.queryForList(
            "$VISIBLE_CTE SELECT * FROM visible $where $ORDER LIMIT ? OFFSET ?",
            *(args + listOf(limit, offset)).toTypedArray(),
        )
    }

    fun count(term: String, nameMatchedRulesetIds: Collection<String>): Long {
        val (where, args) = where(term, nameMatchedRulesetIds)
        return jdbc.queryForObject("$VISIBLE_CTE SELECT COUNT(*) FROM visible $where", Long::class.java, *args.toTypedArray()) ?: 0L
    }
}
