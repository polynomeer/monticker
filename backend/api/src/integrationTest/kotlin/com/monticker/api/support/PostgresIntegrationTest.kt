package com.monticker.api.support

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * 실제 Postgres 컨테이너 + Flyway 마이그레이션을 사용하는 통합 테스트의 공통 베이스.
 * Spring 컨텍스트를 띄우지 않고(무겁고 이 모듈의 Kafka/Redis/ES 등 인프라를 함께 요구함)
 * DataSource와 JdbcTemplate만 직접 구성한다 — CacheConfigIntegrationTest가 이미 쓰던
 * "빈을 수동으로 조립" 관례를 따른다.
 *
 * 컨테이너와 마이그레이션은 companion object(하위 클래스 간 공유)에서 지연 초기화되어
 * 이 베이스를 상속하는 여러 통합 테스트 클래스가 컨테이너 기동과 마이그레이션 비용을
 * 한 번만 지불하도록 한다.
 */
@Testcontainers
abstract class PostgresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("monticker")
                .withUsername("monticker")
                .withPassword("monticker")

        @JvmStatic
        val dataSource: DataSource by lazy {
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .apply { setDriverClassName(postgres.driverClassName) }
                .also { ds -> Flyway.configure().dataSource(ds).load().migrate() }
        }
    }

    protected val jdbcTemplate: JdbcTemplate by lazy { JdbcTemplate(dataSource) }
}
