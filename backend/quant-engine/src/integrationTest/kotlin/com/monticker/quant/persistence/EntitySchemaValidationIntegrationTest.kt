package com.monticker.quant.persistence

import com.monticker.quant.support.PostgresIntegrationTest
import jakarta.persistence.Entity
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter

/**
 * quant-engine은 스키마를 소유하지 않고 `ddl-auto: validate`로 api가 마이그레이션한 스키마를 검증만 한다.
 * 그래서 `@EntityScan` 패키지 안의 @Entity가 api 스키마와 어긋나면 기동 자체가 실패한다 — 실제로
 * `RuleSet`이 V22에서 DROP된 `rule_sets`를 가리키는 @Entity로 남아 있었다. 기동 시 validate가
 * 보는 것과 같은 검사를 실제 스키마에 대고 미리 돌린다.
 */
class EntitySchemaValidationIntegrationTest : PostgresIntegrationTest() {

    // QuantEngineApplication의 @EntityScan(basePackages)와 같은 목록
    private val entityScanPackages = listOf(
        "com.monticker.api.quant.domain",
        "com.monticker.api.analytics.domain",
        "com.monticker.api.backtest.domain",
    )

    @Test
    fun `every scanned quant-engine entity validates against the api-migrated schema`() {
        jdbcTemplate.execute("SELECT 1")   // Flyway 마이그레이션을 먼저 돌린다

        val scanner = ClassPathScanningCandidateComponentProvider(false).apply { addIncludeFilter(AnnotationTypeFilter(Entity::class.java)) }
        val entities = entityScanPackages.flatMap { scanner.findCandidateComponents(it) }.map { Class.forName(it.beanClassName) }
        assertThat(entities).isNotEmpty

        val configuration = Configuration()
            .setProperty("hibernate.connection.url", postgres.jdbcUrl)
            .setProperty("hibernate.connection.username", postgres.username)
            .setProperty("hibernate.connection.password", postgres.password)
            .setProperty("hibernate.hbm2ddl.auto", "validate")
        entities.forEach { configuration.addAnnotatedClass(it) }

        configuration.buildSessionFactory().close()   // validate 실패 시 SchemaManagementException으로 던진다
    }
}
