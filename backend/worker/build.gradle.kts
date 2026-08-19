plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.0"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "1.9.25"
}

dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
	}
}

group = "com.monticker"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-tracing-bridge-otel")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("io.opentelemetry:opentelemetry-api")
	implementation("io.github.resilience4j:resilience4j-kotlin:2.2.0")
	implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
	implementation("org.springframework.boot:spring-boot-starter-aop")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.kafka:spring-kafka")
	implementation("org.springframework.retry:spring-retry")
	implementation("org.springframework.integration:spring-integration-core")
	implementation("org.springframework.integration:spring-integration-kafka")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("com.google.guava:guava:33.3.1-jre")
	implementation("com.anthropic:anthropic-java:2.34.0")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("io.mockk:mockk:1.13.10")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── 통합 테스트 소스셋 ──────────────────────────────────────────
// `test`(단위, mock 기반, 빠름)와 분리된 `integrationTest`(실제 Postgres/Redis/Kafka
// 컨테이너 기반, 느림) 소스셋. src/integrationTest/kotlin 에 위치.
// 실행: ./gradlew integrationTest  (CI에서는 `test`와 별도 스텝으로 실행)
sourceSets {
	create("integrationTest") {
		kotlin.srcDir("src/integrationTest/kotlin")
		resources.srcDir("src/integrationTest/resources")
		compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
		runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
	}
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
	"integrationTestImplementation"("org.testcontainers:junit-jupiter")
	"integrationTestImplementation"("org.testcontainers:postgresql")
}

val integrationTest = tasks.register<Test>("integrationTest") {
	description = "실제 Postgres/Redis 컨테이너로 통합 테스트를 실행한다 (DB/Redis를 목킹하지 않음)."
	group = "verification"
	testClassesDirs = sourceSets["integrationTest"].output.classesDirs
	classpath = sourceSets["integrationTest"].runtimeClasspath
	useJUnitPlatform()
	shouldRunAfter(tasks.test)
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
