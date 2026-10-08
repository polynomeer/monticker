package com.monticker.api.quant

import com.monticker.api.quant.infrastructure.MARKET_VISIBLE_FROM
import com.monticker.api.quant.infrastructure.StrategyMarketSearchRepository
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 전략 마켓 서버 측 검색 SQL을 실제 Postgres에서 검증한다 — 설명·닉네임 ILIKE, 이름 매칭 ID 목록 OR,
 * 와일드카드 이스케이프, 페이지·총수, 그리고 결과가 목록의 공개 범위(MARKET_VISIBLE_FROM)를 벗어나지 않는지.
 * 다른 테스트가 만든 마켓 행도 섞이므로 고유 토큰으로 내 행만 본다.
 */
class StrategyMarketSearchIntegrationTest : PostgresIntegrationTest() {

    private val repo by lazy { StrategyMarketSearchRepository(jdbcTemplate) }

    private fun token() = UUID.randomUUID().toString().replace("-", "").take(10)

    private fun user(nickname: String): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "ms-${token()}@example.com", nickname,
    )!!

    private fun strategy(userId: Long, description: String?, subscribers: Int = 0): String {
        val rulesetId = token() + token().take(14)   // VARCHAR(24)
        jdbcTemplate.update(
            "INSERT INTO strategy_market (ruleset_id, user_id, description, subscribe_count) VALUES (?, ?, ?, ?)",
            rulesetId, userId, description, subscribers,
        )
        return rulesetId
    }

    private fun ids(rows: List<Map<String, Any?>>) = rows.map { it["ruleset_id"] as String }

    @Test
    fun `description and nickname match case-insensitively and name matches come in by id`() {
        val t = token()
        val author = user("Nick$t")
        val other = user("someone-${token()}")
        val byDesc = strategy(other, "Breakout ${t.uppercase()} 전략", subscribers = 5)
        val byNick = strategy(author, "설명 없음", subscribers = 3)
        val byName = strategy(other, "무관한 설명")
        strategy(other, "전혀 관계없음")

        val rows = repo.search(t.lowercase(), listOf(byName), limit = 20, offset = 0)
        assertThat(ids(rows)).containsExactly(byDesc, byNick, byName)   // 구독 많은 순
        assertThat(rows[1]["author_nickname"]).isEqualTo("Nick$t")
        assertThat(repo.count(t, listOf(byName))).isEqualTo(3L)

        // 이름 매칭 목록이 비면 설명·닉네임만
        assertThat(ids(repo.search(t, emptyList(), 20, 0))).containsExactly(byDesc, byNick)
    }

    @Test
    fun `wildcards in the term are literal`() {
        val t = token()
        val u = user("w-${token()}")
        val literal = strategy(u, "수익률 50%_$t")
        strategy(u, "수익률 50X$t")   // '_'가 와일드카드로 해석되면 이것도 걸린다

        assertThat(ids(repo.search("50%_$t", emptyList(), 20, 0))).containsExactly(literal)
        assertThat(ids(repo.search("_$t", emptyList(), 20, 0))).containsExactly(literal)
        assertThat(ids(repo.search("%$t", emptyList(), 20, 0))).isEmpty()   // '%' 뒤에 바로 토큰이 오는 행은 없다
    }

    @Test
    fun `pages with limit and offset and never leaves the market list visibility`() {
        val t = token()
        val u = user("p-${token()}")
        val all = (1..5).map { strategy(u, "page-$t-$it", subscribers = 10 - it) }

        assertThat(ids(repo.search("page-$t", emptyList(), 2, 0))).containsExactly(all[0], all[1])
        assertThat(ids(repo.search("page-$t", emptyList(), 2, 4))).containsExactly(all[4])
        assertThat(repo.count("page-$t", emptyList())).isEqualTo(5L)

        val visible = jdbcTemplate.queryForList("SELECT sm.ruleset_id $MARKET_VISIBLE_FROM", String::class.java).toSet()
        assertThat(visible).containsAll(ids(repo.search("page-$t", emptyList(), 20, 0)))
        assertThat(repo.visibleRulesetIds()).isSubsetOf(visible)
        // 공개 목록에 없는 ID를 이름 매칭으로 넘겨도 결과에 생기지 않는다
        assertThat(repo.search("page-$t", listOf("not-in-market-$t"), 20, 0)).hasSize(5)
    }
}
