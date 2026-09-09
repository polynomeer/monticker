package com.monticker.api.support

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
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
 * 한 번만 지불하도록 한다. 의도적으로 @Testcontainers/@Container를 쓰지 않는다 — 그
 * JUnit5 확장은 컨테이너를 "쓰는 각 테스트 클래스"의 afterAll에 stop()을 걸기 때문에,
 * 같은 static 필드를 상속으로 공유하는 이 베이스에서는 먼저 끝난 클래스가 아직 실행
 * 중인 다른 클래스의 컨테이너를 죽여버리는 실제 레이스가 있었다(같은 스위트 안에서
 * 매번 다른 클래스가 "Connection refused"로 실패 — 항상 가장 나중에 실행된 클래스였다).
 * 컨테이너를 직접 start()하고 절대 stop()을 호출하지 않는다 — JVM이 끝나면 Testcontainers의
 * Ryuk 리퍼가 정리한다.
 */
abstract class PostgresIntegrationTest {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> by lazy {
            PostgreSQLContainer(DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("monticker")
                .withUsername("monticker")
                .withPassword("monticker")
                .apply { start() }
        }

        @JvmStatic
        val dataSource: DataSource by lazy {
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .apply { setDriverClassName(postgres.driverClassName) }
                .also { ds -> Flyway.configure().dataSource(ds).load().migrate() }
        }
    }

    protected val jdbcTemplate: JdbcTemplate by lazy { JdbcTemplate(dataSource) }
}
