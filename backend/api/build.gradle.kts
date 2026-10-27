plugins {
	kotlin("jvm") version "2.4.21"
	kotlin("plugin.spring") version "2.4.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.4.21"
}

// Spring Modulith BOM
dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:2.1.1")
		mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
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
	// Spring Modulith (Outbox Pattern: JPA 발행 스토어 + Kafka 외부화)
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	implementation("org.springframework.modulith:spring-modulith-starter-jpa")
	implementation("org.springframework.modulith:spring-modulith-events-api")
	implementation("org.springframework.modulith:spring-modulith-events-kafka")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	// Boot 4는 자동 구성이 기술별 모듈로 나뉘었다 — 라이브러리만 두면 자동 구성이 붙지 않는다. starter로 받는다
	implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
	implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
	implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-aspectj")
	// Boot 4의 starter-batch는 메모리 저장소다 — JDBC 저장소가 아니면 정산·청소 잡 이력이 BATCH_* 테이블에 남지 않는다
	implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	// Boot 매퍼(MVC·Modulith 아웃박스·Kafka)는 Jackson 3다(ADR-104). Kotlin 모듈은 Jackson 3용을 따로 받는다
	implementation("tools.jackson.module:jackson-module-kotlin")
	// 직접 만든 매퍼는 B4-6까지 Jackson 2로 남는다 — Boot 4는 Jackson 2를 끌어오지 않으니 명시한다
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
	// Boot 4는 flyway-core만으로는 Flyway를 자동 구성하지 않는다 — starter가 없으면 마이그레이션이 조용히 안 돈다
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	runtimeOnly("org.postgresql:postgresql")
	implementation("io.jsonwebtoken:jjwt-api:0.13.0")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
	implementation("com.anthropic:anthropic-java:2.73.0")
	implementation("io.github.resilience4j:resilience4j-kotlin:2.4.0")
	implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")
	implementation("io.github.resilience4j:resilience4j-micrometer:2.4.0")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	// Boot 4는 MockMvc 등 기술별 테스트 지원도 모듈로 나뉘었다
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	// Boot 4 — @WebMvcTest가 SecurityConfig(HttpSecurity)를 띄우려면 보안 테스트 자동 구성 모듈이 있어야 한다
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("io.mockk:mockk:1.14.11")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
	implementation("net.logstash.logback:logstash-logback-encoder:9.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── 통합 테스트 소스셋 ──────────────────────────────────────────
// `test`(단위, mock 기반, 빠름)와 분리된 `integrationTest`(실제 Postgres/Redis
// 컨테이너 기반, 느림) 소스셋. src/integrationTest/kotlin 에 위치.
// 실행: ./gradlew integrationTest  (CI에서는 `test`와 별도 스텝으로 실행)
sourceSets {
	create("integrationTest") {
		kotlin.srcDir("src/integrationTest/kotlin")
		// src/integrationTest/resources는 소스셋 관례 경로라 따로 더하지 않는다 — 더하면 같은 파일이 두 번 복사돼 빌드가 실패한다
		compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
		runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
	}
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
	"integrationTestImplementation"("org.testcontainers:testcontainers-junit-jupiter")
	"integrationTestImplementation"("org.testcontainers:testcontainers-postgresql")
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
		freeCompilerArgs.addAll(
			"-Xjsr305=strict",
			// K2(2.2+)는 생성자 프로퍼티 파라미터의 어노테이션(@Value·@Qualifier·@JsonProperty 등)이 앞으로 필드에도
			// 붙는다고 경고한다. Kotlin 1.9와 같은 동작(파라미터에만)을 명시해 고정한다 — 바꾸려면 별도 PR로 영향부터 본다.
			"-Xannotation-default-target=first-only",
		)
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// ADR-093 — api·worker가 함께 지키는 사례표(알림 발송 정책). 두 빌드가 코드를 공유할 수 없어 표를 공유한다.
	systemProperty("monticker.contractsDir", file("../contracts").absolutePath)
}
