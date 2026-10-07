package com.monticker.worker.support

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * 실제 Postgres 컨테이너 + Flyway 마이그레이션을 사용하는 통합 테스트의 공통 베이스.
 * Spring 컨텍스트를 띄우지 않고 DataSource와 JdbcTemplate만 직접 구성한다
 * (backend/api의 PostgresIntegrationTest와 동일한 관례).
 *
 * worker 모듈은 스키마를 직접 소유하지 않는다(application.yml: flyway.enabled=false,
 * jpa.hibernate.ddl-auto=validate) — api 모듈이 Flyway로 마이그레이션한 스키마를 검증만
 * 하는 소비자다. 그래서 여기서는 api의 db/migration을 그대로 재생시켜 실제 스키마와
 * 동일한 컨테이너를 띄운다. 그 마이그레이션 경로는 build.gradle.kts의 integrationTest
 * 태스크가 system property(monticker.apiMigrationsDir)로 넘겨준다.
 *
 * 컨테이너와 마이그레이션은 companion object(하위 클래스 간 공유)에서 지연 초기화되어
 * 이 베이스를 상속하는 여러 통합 테스트 클래스가 컨테이너 기동과 마이그레이션 비용을
 * 한 번만 지불하도록 한다.
 *
 * @Testcontainers/@Container를 쓰지 않는다 — 그 JUnit5 확장은 컨테이너를 쓰는 각 테스트 클래스의 afterAll에
 * stop()을 걸어, 상속으로 같은 static 필드를 공유하는 이 베이스에서는 먼저 끝난 클래스가 다음 클래스의 컨테이너를
 * 꺼버린다. 하위 클래스가 스모크 테스트 하나뿐이던 동안은 드러나지 않다가 두 번째(RealtimeCoveragePublisher
 * IntegrationTest, ADR-060)가 생기자 매번 "Failed to obtain JDBC Connection"으로 재현됐다. api 베이스가 이미 겪고
 * 고친 것과 같은 레이스다 — 같은 방식으로 직접 start()하고 stop()하지 않는다(JVM 종료 시 Ryuk가 정리한다).
 */
abstract class PostgresIntegrationTest {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer by lazy {
            PostgreSQLContainer(DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("monticker")
                .withUsername("monticker")
                .withPassword("monticker")
                .apply { start() }
        }

        @JvmStatic
        val dataSource: DataSource by lazy {
            val migrationsDir = requireNotNull(System.getProperty("monticker.apiMigrationsDir")) {
                "monticker.apiMigrationsDir system property가 없습니다 — integrationTest Gradle 태스크로 실행했는지 확인하세요."
            }
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .apply { setDriverClassName(postgres.driverClassName) }
                .also { ds -> Flyway.configure().dataSource(ds).locations("filesystem:$migrationsDir").load().migrate() }
        }
    }

    protected val jdbcTemplate: JdbcTemplate by lazy { JdbcTemplate(dataSource) }
}
