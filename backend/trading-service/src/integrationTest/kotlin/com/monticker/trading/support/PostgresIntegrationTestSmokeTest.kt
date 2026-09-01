package com.monticker.trading.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * PostgresIntegrationTest 자체가 옳게 배선됐는지 확인하는 스모크 테스트.
 * V12__seed_stocks.sql이 실제로 적용됐는지(= api의 마이그레이션 경로를 제대로
 * 찾아 재생했는지)를 stocks 테이블 row 존재로 검증한다.
 */
class PostgresIntegrationTestSmokeTest : PostgresIntegrationTest() {

    @Test
    fun `api's Flyway migrations are replayed against the container, seed data included`() {
        val stockCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stocks", Long::class.java)

        assertThat(stockCount).isGreaterThan(0)
    }
}
