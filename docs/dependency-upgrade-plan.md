# 메이저 의존성 업그레이드 계획

> Read this when: 2026-10-07에 닫은 dependabot 메이저 PR(#101~#104, #106, #108, #109, #112~#114, #120~#123)을 다시 올릴 때. 순서와 묶음을 정해 둔 문서다.

dependabot은 의존성마다 PR을 하나씩 연다. 메이저는 대개 **짝이 있는 의존성과 함께**, **선행 조건을 갖춘 뒤**에 올려야 해서 하나씩은 받을 수 없었다(닫은 사유는 각 PR 코멘트에 있다). 이 문서는 그 묶음을 작업 단위(트랙)로 다시 짠다.

버전별 변경 사항은 이 문서를 쓴 시점의 이해다. **각 트랙의 0단계는 공식 마이그레이션 가이드를 읽고 아래 "확인할 것"을 사실로 바꾸는 일이다.** 확인한 내용이 다르면 이 문서를 고친다.

---

## 1. 한눈에

| 트랙 | 대상 | 닫은 PR | 선행 조건 | 크기 | 위험 |
|---|---|---|---|---|---|
| **B0** Gradle 9 | Gradle wrapper 8.14 → 9.x (api·worker) | #135 #138 | **B1**(Kotlin 플러그인 2.0+) | 소 | 하 — 확인 결과 다른 걸림돌 없음 |
| **B1** Kotlin 2 | `kotlin("jvm"/"plugin.spring"/"plugin.jpa")` 1.9.25 → 2.x (api·worker) | #106 #108 #109 | 없음 | 중 | 중 — K2 컴파일러가 686개 파일을 다시 본다 |
| **B2** Testcontainers 2 | `testcontainers-bom` 1.20 → 2.x | #101 | 없음 | 소 | 하 — 테스트 전용, 8개 파일 |
| **B3** jjwt 0.13 | `jjwt-api`·`jjwt-impl`·`jjwt-jackson` 0.12.6 → 0.13 | #102 | 없음 | 소 | **상** — 인증 경로 |
| **B4** Spring Boot 4 | Boot 3.5 → 4.x + Modulith 2 + logstash-logback-encoder 9 | #103 #104 | **B1**(Kotlin 2.2+), B2 권장 | **대** | **상** — 실거래 경로 전체 |
| **W1** Next 16 | `next`·`eslint-config-next` 15 → 16, ESLint flat config | #123, #112 일부 | 없음 | 중 | 중 |
| **W2** Tailwind 4 | `tailwindcss` 3 → 4 | #112 일부 | W1 권장 | 중 | 중 — 화면 전체의 시각 회귀 |
| **W3** 툴체인 | TypeScript 7, vitest 5, jsdom 30, @types/node | #112 일부 | W1 | 소~중 | 하 |
| **M1** Expo SDK | Expo 52 → 58 (expo-router·expo-notifications·RN·React 19·Babel 8) | #113 #114 #120, #121 모바일분 | 없음 | 중 | 중 — 앱은 작지만(TS 7개) 6개 SDK를 건넌다 |

**권장 순서**: B2(완료) → B1 → B0 → B3 → (W1 → W2 → W3, M1은 병렬) → B4. 백엔드 트랙은 실거래 경로에 걸리므로 한 번에 하나씩, 각각 독립 PR로 머지하고 며칠 운영(또는 로컬 장시간 기동)을 거친 뒤 다음으로 간다. 웹·모바일 트랙은 백엔드와 독립이라 병렬로 진행해도 된다.

---

## 2. 공통 원칙

- **PR 하나에 트랙 하나.** 실패했을 때 원인이 하나로 좁혀지고, 되돌릴 때도 한 번에 되돌린다.
- **동작 변경과 섞지 않는다.** 업그레이드 PR은 컴파일·설정·API 이름 변경만 담는다. 김에 고치고 싶은 것은 별도 PR로.
- **검증 기준은 CI 전체 + 트랙별 추가 검증.** 기본은 `scripts/check.sh --full`(CI와 같은 명령)과 e2e-ci다. 트랙마다 아래 "검증"이 추가된다.
- **dependabot이 같은 PR을 다시 열지 않게 한다.** 트랙을 시작하기 전까지는 해당 메이저를 `.github/dependabot.yml`의 `ignore`에 두고, 짝이 있는 의존성은 `groups`로 묶는다(§5). 트랙이 끝나면 ignore를 지운다.
- **결정이 생기면 ADR.** 대안 중 하나를 고르는 일이 생기면(예: B4의 Jackson 3 전환 방식) CLAUDE.md 규칙대로 ADR을 남긴다.

---

## 3. 백엔드 트랙

### B0. Gradle 8.14 → 9.x

2026-10-07 dependabot 재실행에서 새로 나왔다(#135 #138). 빌드 도구라 운영 코드는 바뀌지 않지만, 플러그인(Spring Boot·dependency-management·Kotlin)과 Gradle 9의 호환을 먼저 본다.

1. 두 모듈의 wrapper를 같은 버전으로 올린다(`./gradlew wrapper --gradle-version …`). wrapper jar·스크립트도 함께 갱신된다.
2. deprecated 경고(`--warning-mode all`)를 8.14에서 먼저 0으로 만든다. Gradle 9는 8.x의 deprecated API를 제거했다.
3. 커스텀 소스셋(`integrationTest`)과 `configurations[...]` 접근 방식이 그대로 동작하는지 확인한다.

**확인 결과**(2026-10-07):
- **Gradle 9.0부터 Kotlin Gradle 플러그인은 2.0.0 이상이어야 한다**([Gradle 9 업그레이드 가이드](https://docs.gradle.org/current/userguide/upgrading_major_version_9.html)). 1.9.25는 Gradle 9가 제거한 API에 의존한다. → **B1(Kotlin 2) 뒤로 미뤘다.**
- Gradle 9 데몬은 JVM 17+ — 우리는 21이라 문제없다.
- Spring Boot 3.5.x Gradle 플러그인은 Gradle 9를 지원한다(현재 3.5.16).
- `junit-platform-launcher`는 이미 `testRuntimeOnly`로 선언돼 있다(Gradle 9는 자동으로 넣지 않는다).
- **9.8.0으로 올림**(B1 위에서): 빌드 스크립트 변경 없이 wrapper만 바꿨다. `--warning-mode all`의 deprecated 경고는 `Configuration.setVisible`(플러그인 쪽, Gradle 11에서 제거 예정) 하나다. Docker 빌드 단계(eclipse-temurin 21 alpine)도 통과.
- 8.14.5에서 `--warning-mode all`로 compile·bootJar를 돌리면 deprecated 경고가 하나만 나온다(`StartParameter.isConfigurationCacheRequested`, 플러그인 쪽, **Gradle 10**에서 제거 예정). Gradle 9를 막는 것은 Kotlin 플러그인 버전뿐이다. B1을 마치면 B0은 wrapper만 올리면 될 것으로 본다.
**검증**: api·worker `test`·`integrationTest`, `bootJar`·Docker 이미지 빌드(deploy-images).

### B1. Kotlin 1.9 → 2.x

Kotlin 컴파일러 플러그인(`plugin.spring`·`plugin.jpa`)은 `kotlin("jvm")`과 **반드시 같은 버전**이다. dependabot은 이 셋을 따로 올려 버전이 어긋났다(#106 #108 #109). Boot 4가 Kotlin 2.x를 요구하므로 B4의 선행 조건이기도 하다. Boot 3.5에서 먼저 올려 두면 B4의 변경 범위가 줄어든다.

1. api·worker의 세 플러그인을 같은 버전으로 올린다. 가능하면 Boot 4가 기준으로 삼는 Kotlin 버전에 맞춘다.
2. K2 컴파일러가 새로 내는 경고와 오류를 정리한다. 스마트 캐스트, 오버로드 해석, `@JvmStatic`·`companion` 관련이 흔하다.
3. `kotlin-reflect`와 `jackson-module-kotlin`이 새 Kotlin과 호환되는지 본다. 그 위에서 data class 역직렬화(Kafka 메시지, API 요청 DTO)가 그대로 동작하는지 테스트로 확인한다.
4. `freeCompilerArgs`(`-Xjsr305=strict`)가 그대로 유효한지 확인한다.

**확인 결과**(2026-10-07, **2.3.21**로 올림):
- 목표 버전은 Spring Boot 4.1.1 BOM의 `kotlin.version`(2.3.21)에 맞췄다 — B4에서 Kotlin을 다시 올리지 않는다. Boot 3.5.16 Gradle 플러그인이 `kotlin.version`을 플러그인 버전에 맞춰 줘서 런타임 `kotlin-reflect`도 2.3.21로 바뀐다. `jackson-module-kotlin`은 Boot BOM의 2.21.4.
- K2가 낸 **오류는 1건**: JDBC 가변 인자 배열이 `Long`·`Int`의 교집합 타입으로 추론됐다(`QuantSignalFeedService`). `toTypedArray<Any>()`로 명시했다 — JDBC는 원래 `Object[]`를 받으므로 동작 같음.
- 생성자 프로퍼티 파라미터의 어노테이션(`@Value` 30·`@Qualifier` 14·`@JsonProperty` 2·`@DateTimeFormat` 2)이 "앞으로 필드에도 붙는다"는 경고 → `-Xannotation-default-target=first-only`로 **1.9와 같은 동작을 고정**했다. 새 기본값(param-property)으로 바꾸는 건 별도 작업.
- 남은 경고: Java deprecated API 사용(기존), 불필요한 `!!`·`?.`, 통합 테스트 한 곳의 checkerframework 타입 어노테이션 접근 경고(Kotlin 2.4에서 오류가 된다, KT-80247 — 2.4로 올릴 때 처리).
- `allOpen`(JPA 엔티티)은 그대로 동작한다 — 엔티티 매핑 통합 테스트 통과.
**검증**: api·worker `test` + `integrationTest`. `ModulithStructureTest`(모듈 경계)가 통과해야 한다. `JsonbColumnMappingTest`처럼 리플렉션으로 엔티티를 훑는 테스트에 주의한다.

### B2. Testcontainers 1.20 → 2.x

테스트 전용이라 가장 안전한 메이저다. 다른 트랙을 시작하기 전에 끝내 두면 이후 트랙의 통합 테스트가 새 버전 위에서 돈다.

1. BOM을 올리고 컴파일 오류를 따라간다. 쓰는 범위는 `PostgreSQLContainer`·`GenericContainer`·`DockerImageName`과 JUnit5 확장(`@Testcontainers`·`@Container`)으로, 8개 파일이다.
2. 공유 컨테이너 베이스(`PostgresIntegrationTest`)의 "직접 start(), stop() 안 함" 관례는 유지한다. 이 클래스 주석에 적힌 컨테이너 공유 레이스 때문이다.

**확인 결과**(2.0.5, [릴리스 노트](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0)):
- 모듈 아티팩트에 `testcontainers-` 접두사가 붙었다: `junit-jupiter` → `testcontainers-junit-jupiter`, `postgresql` → `testcontainers-postgresql`.
- 컨테이너 클래스가 모듈 패키지로 옮겨졌다: `org.testcontainers.containers.PostgreSQLContainer` → `org.testcontainers.postgresql.PostgreSQLContainer`. 새 클래스는 **제네릭이 아니다**(`PostgreSQLContainer<*>` → `PostgreSQLContainer`). `GenericContainer`·`DockerImageName`·JUnit5 확장(`org.testcontainers.junit.jupiter`)은 그대로다.
- JUnit4 지원 제거, 모듈 기본 생성자 제거 — 우리는 JUnit5만 쓰고 항상 이미지를 넘겨 생성하므로 영향 없음.

**검증**: api·worker `integrationTest` 전부.

### B3. jjwt 0.12 → 0.13

`jjwt-api`만 올리면 런타임 모듈(`jjwt-impl`·`jjwt-jackson`)과 버전이 어긋난다(#102). **세 아티팩트를 함께 올린다.** 사용처는 `JwtTokenProvider` 한 곳이지만, 모든 인증이 이 경로를 지난다.

1. 세 아티팩트를 같은 버전으로 올린다.
2. 서명·검증 API의 deprecated·제거 항목을 따라간다.
3. **이전 버전이 발급한 토큰을 새 버전이 검증하는지** 테스트로 고정한다. 배포 직후 로그인된 사용자가 전부 로그아웃되면 안 된다. 리프레시 토큰도 같다.

**확인 결과**(2026-10-07, **0.13.0**으로 올림, [CHANGELOG](https://github.com/jwtk/jjwt/blob/master/CHANGELOG.md)):
- 0.13.0의 문서화된 변경은 둘뿐이다: Java 7을 지원하는 마지막 minor라는 공지(0.14부터 Java 8+), `JacksonDeserializer(ObjectMapper, Map)` 생성자 공개. 파싱·검증·키 길이 검사 변경은 없다.
- 그 사이 dependabot #132로 세 아티팩트가 0.12.7이 됐고, 이번에 셋을 함께 0.13.0으로 올렸다.
- 개발용 기본 키(`application.yml`)는 55바이트라 HMAC 최소 길이(32바이트)를 넘는다. `Keys.hmacShaKeyFor` 사용은 그대로다.
- `JwtTokenCompatibilityTest`: 0.12.7이 발급한 access·refresh 토큰(만료 2100년)을 고정해 두고 검증한다 — 0.12.7과 0.13.0 모두 통과. 서명을 바꾼 토큰은 계속 거부한다. 다음 jjwt 업그레이드나 키·iss·aud 변경 때도 이 테스트가 기준이다.

**검증**: 인증 단위·통합 테스트, e2e 로그인 흐름(CI).

### B4. Spring Boot 3.5 → 4.1

가장 큰 트랙이다. 실거래 주문 경로(ADR-055~063)가 전부 이 위에 있다. B0~B3·W·M을 마친 뒤 2026-10-13에 0단계(영향 조사)를 했다.

**목표 버전**: Spring Boot **4.1.1** — Framework 7.0.9, Batch 6.0.5, Kafka 4.1.1, Security 7.1.1, Integration 7.1.1, Data 2026.0.1, Hibernate 7.4.5, Flyway 12.4.0, Jackson **3.1.5**(Jackson 2 BOM 2.21.5도 계속 관리), Elasticsearch Java client **9.4.5**, JUnit Jupiter 6, Tomcat 11, Kotlin 2.3.21(B1에서 맞춤), Modulith **2.1.x**.

#### 조사 결과 — 빌드는 되는데 조용히 깨지는 것(가장 위험)
- **Flyway가 안 돈다**: Boot 4는 `spring-boot-starter-flyway`가 있어야 Flyway를 자동 구성한다. 지금처럼 `flyway-core`만 있으면 마이그레이션이 실행되지 않는다(api·worker).
- **Batch 이력이 메모리로 간다**: `spring-boot-starter-batch`는 이제 메모리 저장소다. `spring-boot-starter-batch-jdbc`로 바꾸고 `@EnableBatchProcessing`에 `@EnableJdbcJobRepository`(또는 `JdbcDefaultBatchConfiguration`)를 둔다. 그렇지 않으면 정산·결제 청소 잡의 실행 이력이 BATCH_* 테이블에 남지 않는다.
- **ES 클라이언트 9 vs 서버 8.18**: Elastic은 클라이언트 ≤ 서버 버전만 보장한다. 실패해도 검색이 DB 폴백으로 넘어가 드러나지 않는다(CH-04와 같은 유형). **서버를 먼저 9로** 올린다(클라이언트 8.18 → 서버 9는 REST 호환 헤더로 동작해야 하지만 확인할 것).

#### 조사 결과 — 막히는 것과 반드시 할 것
| 항목 | 결과 | 처리 |
|---|---|---|
| spring-statemachine 4.0.2 | **유지보수 종료·Boot 4 미지원**(저장소 archived, 5.x 없음) | **B4-1에서 제거 완료** — 순수 전이표로 교체([ADR-103](decisions/103-replace-spring-statemachine.md)) |
| spring-retry | 저장소 archived, Boot 4.1 BOM에서 빠짐(버전 없이 선언하면 해석 실패) | 쓰는 곳은 `@RetryableTopic(backoff = @Backoff)` 5곳뿐 → Spring Kafka 4의 `backOff = @BackOff`로 바꾸고 의존성 제거. 4.1에서 `sameIntervalTopicReuseStrategy` 기본값이 바뀌었으니 재시도 토픽 이름이 KafkaTopicConfig 선언과 맞는지 확인 |
| Modulith 1.4 → 2.1 | `event_publication`(·archive)에 `status`·`completion_attempts`·`last_resubmission_date` 컬럼 추가. api는 `ddl-auto: validate`라 없으면 기동 실패 | Flyway로 컬럼 추가(public, `worker_outbox` 둘 다). nullable 추가라 **Boot 3.5에서 먼저 넣어도 무해** |
| Spring Batch 6 | 시퀀스 `BATCH_JOB_SEQ` → `BATCH_JOB_INSTANCE_SEQ`, 패키지 이동(`batch.item.*` → `batch.infrastructure.item.*` 등 ~19파일), `chunk(n, tx)` → `.chunk(n).transactionManager(tx)` 8곳, `JobLauncher` → `JobOperator` | 업그레이드 PR에서. 배포 전 FAILED 상태의 v5 잡 인스턴스를 정리(6은 재시작 못 함) |
| Jackson 3 | 커스텀 직렬화기·`ObjectMapper` 빈 없음. Boot 매퍼 주입 ~26곳, 직접 만든 매퍼 ~30곳. 기본값 변경(날짜 ISO 문자열, 속성 알파벳 정렬, primitive null 거부, Kotlin StrictNullChecks) | 아래 B4-3·B4-6 |
| 시작 starter 이름 | web → `webmvc`, aop → `aspectj`, oauth2-client → `security-oauth2-client`, + `flyway`, batch → `batch-jdbc`, 기술별 test starter | 업그레이드 PR |
| springdoc 2.5 | 3.1.1이 Boot 4.1 기준 | 업그레이드 PR(쓰는 곳은 `OpenApiConfig` 1파일) |
| logstash-logback-encoder 7.4 | 9.0은 Jackson 3 기반 | 업그레이드 PR(`logback-spring.xml` 필드 이름 불변 확인) |
| 테스트 | `@MockBean` 제거 → `@MockitoBean`(4파일), `@SpringBootTest`가 MockMvc를 자동 구성하지 않음(16파일 확인) | 업그레이드 PR |
| Hibernate 7·Flyway 12·Security 7·Integration 7 | 우리 사용 범위에서 깨지는 API는 찾지 못함(Session API 미사용, lambda DSL 사용 중) | 통합 테스트로 확인. `spring.flyway.postgresql.transactional-lock` 키가 4.1에 남았는지 확인(CONCURRENTLY 마이그레이션 6개가 의존) |
| resilience4j 2.4·anthropic-java | 문제 없음(Jackson 2와 공존) | — |

#### 작업 단위 (PR 하나씩, 위에서부터)
- **B4-1** ✅ spring-statemachine 제거 — ADR-103. Boot 3.5에서 먼저.
- **B4-2** ✅ Modulith v2 컬럼 Flyway 마이그레이션(V101, Boot 3.5에서 먼저, nullable 추가). **값 채우기는 필요 없다**: Modulith 2.1.1 소스의 재전송 쿼리가 `status = FAILED or (status is null and completionDate is null)`이라 마이그레이션 전에 쓰인 미완료 행도 집는다. B4-5에서 볼 것: 2.x의 `resubmitIncompletePublicationsOlderThan`이 처리 중(PUBLISHED) 행을 어떻게 다루는지(Staleness Monitor가 오래된 행을 FAILED로 표시한다) — `OutboxResubmissionConfig`와 맞춰야 한다.
- **B4-3** 직렬화 결과 고정 테스트(Boot 3.5에서 먼저): Kafka 페이로드(`@Externalized` 5종 + worker 프로듀서 — 지금 `Instant`가 숫자로 나간다), `event_publication.serialized_event`·`worker_outbox`의 기존 형식 행을 새 버전이 읽는지, Redis 캐시(`@class` 타입 정보 — TTL ≤ 1시간이라 키 접두사 변경으로 끊어도 된다), Redis 문자열 키, REST 응답 표본.
- **B4-4** Elasticsearch 서버 9로(로컬 이미지 + 운영). 클라이언트 8.18로 서버 9를 먼저 확인.
- **B4-5** Boot 4.1 업그레이드 본체: starter 이름, Flyway·Batch-JDBC 명시, Batch 6 시퀀스 마이그레이션·패키지·API, Kafka `@BackOff`·spring-retry 제거, Modulith 2.1, springdoc 3.1, logstash 9, `@MockitoBean`. Jackson은 Boot 매퍼를 주입받는 곳만 `JsonMapper`로(B4-3 테스트가 초록이어야 한다), 직접 만든 매퍼는 그대로 둔다. Jackson 전환 방식은 **ADR**로 남긴다.
- **B4-6** 직접 만든 Jackson 2 매퍼를 `tools.jackson`으로 한 PR씩(날짜·정렬 설정을 명시해 B4-3이 초록인 채로).

**검증(B4-5)**: `scripts/check.sh --full`, e2e-ci, L-05 order-burst + 정합성 검증(원장 드리프트 0), 카오스 CH-05(Kafka 정지)·CH-13(PG 정지), B4-3 고정 테스트, 운영은 카나리 → 전체(킬 스위치 런북 준비).

---

## 4. 웹·모바일 트랙

### W1. Next 15 → 16 (+ ESLint flat config)

`next`와 `eslint-config-next`는 같은 메이저로 움직인다. #112에 섞여 있던 `eslint-config-next 16`과 ESLint 메이저가 여기에 붙는다.

1. `next`·`eslint-config-next`를 함께 올린다. React는 19.3으로 이미 올렸다(#129).
2. `next lint`는 이미 deprecated 경고가 나고 있다. ESLint CLI로 옮긴다(`.eslintrc.json` → `eslint.config.mjs` flat config). `package.json`의 `lint` 스크립트와 web-ci도 함께 바꾼다.
3. 요청 API(`cookies()`·`headers()`·`params`)의 비동기 전용 전환, 미들웨어 관련 변경, 기본 번들러 변경을 따라간다.
4. 루트 `package.json`의 `packageExtensions`(`next@15.5.25` 키)를 새 버전에 맞춘다. 고정 버전 키 때문에 react-query 5.104에서 타입 검사가 깨진 전례가 있다(#117). 범위 키로 바꿔 둔다.

**확인 결과**(2026-10-07, **16.4.0**으로 올림, [Next 16 업그레이드 가이드](https://nextjs.org/docs/app/guides/upgrading/version-16)):
- 요구 사항: Node 20.9+(CI 22, Docker `node:20-alpine`), TypeScript 5.1+(5.9), React 19.2+(19.3) — 모두 충족.
- 우리에게 해당한 변경:
  - `next lint` 제거, `next.config`의 `eslint` 옵션 제거 → `eslint.config.mjs`(flat config) + `lint: "eslint ."`. eslint-config-next 16은 ESLint 9 이상을 요구해 **ESLint 9.39**로 올렸다(10은 별도 확인 후).
  - eslint-config-next 16이 가져온 `eslint-plugin-react-hooks` 7의 **React Compiler 규칙**이 기존 코드 74곳(54개 파일, 61곳이 `set-state-in-effect`, 2026-10-13 main 기준 — 처음 쟀을 때 61곳에서 그 사이 새 코드로 늘었다)을 잡는다. 동작 변경을 섞지 않으려고 warn으로 두었다 → **후속: 고치고 error로 되돌리기**.
  - **Turbopack이 dev·build 기본값**이 됐다. 커스텀 webpack 설정이 없어 그대로 동작한다. standalone 출력(`.next/standalone/apps/web/server.js`)도 같다.
  - `html`의 `scroll-behavior: smooth`를 Next가 라우트 전환 때 더 이상 덮어쓰지 않는다 → `<html data-scroll-behavior="smooth">`로 15와 같은 동작 유지.
  - 빌드가 `tsconfig.json`을 자동 갱신한다(`jsx: react-jsx`, `.next/dev/types` 포함) — 그대로 커밋.
  - `packageExtensions` 키를 `next@16` 범위로(정확한 버전 키는 패치에서도 깨진다, #142).
- 해당 없음: middleware(없음), 병렬 라우트(없음), `cookies()`/`headers()`(안 씀), `next/image`(안 씀), 동적 라우트 `params`(이미 Promise), runtime config·AMP(안 씀).
**검증**: `tsc`, `scripts/check.sh web --full`(Turbopack 빌드), dev 서버와 운영 빌드(`next start`)에서 홈·종목 상세·로그인 화면 렌더링과 콘솔(CSP 위반·하이드레이션 오류 없음) 확인, e2e-ci.

### W2. Tailwind 3 → 4

설정 방식이 바뀌는 메이저다(JS 설정 → CSS 우선, PostCSS 플러그인 분리, autoprefixer 불필요). 디자인 토큰(Dracula 팔레트, 터미널 디자인 ADR-066)이 `tailwind.config.ts`(106줄)에 있어서 **화면 전체의 시각 회귀**가 위험이다.

**확인 결과**(2026-10-13, **4.3.3**으로 올림, [업그레이드 가이드](https://tailwindcss.com/docs/upgrade-guide)):
- 공식 도구(`@tailwindcss/upgrade`)는 **템플릿·패키지·PostCSS만 받았다**: 클래스 이름 58곳(`outline-none`→`outline-hidden` 27, `rounded`→`rounded-sm` 15, `rounded-sm`→`rounded-xs`, 임의값→표준 클래스 등), `@tailwindcss/postcss` 추가, `autoprefixer` 제거. main과 개수를 대조해 클래스로 쓰인 `rounded` 15곳이 모두 바뀐 것을 확인했다(남은 12개는 JS 변수명).
- 도구의 CSS 변환은 버렸다 — ① 토큰을 CSS `@theme`에 복제하고 JS 설정을 지워 `designTokens.ts`(캔버스 차트도 읽는다)와 값이 두 벌이 된다, ② 일부러 언레이어드로 둔 접근성(고대비·글자 크기) 규칙을 `@layer utilities`로 옮겨 우선순위가 바뀐다, ③ `.dark .glass-surface`를 `@utility dark`로 만들어 html의 `dark` 클래스와 겹친다. 대신 `globals.css`를 직접 고쳤다: `@import "tailwindcss"` + `@config "../../tailwind.config.ts"` + `@custom-variant dark (&:is(.dark *))`(v3 `darkMode: "class"`와 같은 선택자).
- **v3 기본값을 호환 스타일로 유지**: 테두리 기본색(gray-200), placeholder 색(gray-400), 버튼 `cursor: pointer`, `hover`를 항상 적용(v4는 hover 가능한 기기만), **글자 크기별 줄 높이를 절대값으로**(v4는 비율이라 글자 크기를 따로 준 자식의 줄 높이가 커진다 — 로그인 로고 20px → 27px). 지우는 건 별도 결정.
- **JS 설정의 `<alpha-value>`는 v4에서 깨진다**: 호환 계층이 1로 바꾸고 투명도 수식어를 버린다(`bg-up/5` → 불투명). `up`/`down` 색에서 자리표시자를 빼 v4가 `color-mix`로 투명도를 입히게 했다.
- 해당 없음: `ring` 기본색(모든 ring에 색을 명시), `<dialog>`(없음), 커스텀 클래스에 variant(없음), `shadow`·`blur` 이름 변경(안 씀). 테마는 `forcedTheme="dark"`라 라이트 모드는 없다.

**검증**: main과 이 브랜치의 운영 빌드를 나란히 띄워 Playwright로 전체 페이지 스크린샷을 비교했다(허용 오차 0) — 12개 화면(홈·로그인·가입·스크리너·종목·설정·퀀트랩·구독·포트폴리오·관심종목·알림·모의투자)과 고대비+큰 글씨 6개 화면 모두 **픽셀 단위로 같다**(main끼리 반복해도 같아 측정이 안정적임을 먼저 확인). `scripts/check.sh web --full`, `tsc`.
- 후속: v4의 OKLCH 기본 팔레트로 `gray-*` 등이 아주 조금 다르다(`gray-200` rgb(229,231,235) → lab(91.6 …)). 스크린샷 비교에서는 차이가 나오지 않았다.

### W3. TypeScript 7 · vitest 5 · jsdom 30 · @types/node

1. **@types/node는 런타임 Node 메이저에 맞춘다.** dependabot이 제안한 26은 CI(Node 22)·Docker 런타임과 맞지 않는다. Node 런타임을 올릴 때 함께 올린다.
2. **TypeScript 7**: 제거된 옵션(`tsconfig.json`)과 `next build`의 타입 검사 호환을 확인한다. 모바일(`typescript ~5.3`)은 M1에서 Expo가 요구하는 버전을 따른다.
3. vitest·jsdom: 설정 경고(`vitest.config.ts` ESM 경고가 이미 나고 있다)를 정리하고 올린다.

**확인 결과**(2026-10-13):
- **TypeScript 7은 보류, 6.0.3으로 올렸다.** TS 7(Go 네이티브)은 안정된 JS API가 없고, eslint-config-next가 쓰는 typescript-eslint(8.71)는 `typescript >=4.8.4 <6.1.0`만 받는다 — TS 7을 깔면 lint가 실패한다. typescript-eslint는 TS 7.1(새 API)이 나온 뒤 지원할 예정이다. 6.0은 그 범위 안이고 우리 `tsconfig`(target ES2017, moduleResolution bundler)에는 제거된 옵션이 없다. `packages/types`도 함께. → **후속: typescript-eslint가 TS 7을 받으면 7로**.
- **vitest 5·jsdom 30은 Node `^22.12 || ^24 || >=26`을 요구**한다(jsdom은 22.22.2+). 그래서 **Node 24 LTS로 통일**했다: `.nvmrc`, `apps/web` `engines`, web·e2e CI, web Docker(`node:20-alpine` → `node:24-alpine` — Node 20은 2026-04 EOL이었다). `@types/node`도 24. 모바일 CI(20)는 M1에서.
- **jest-dom 7.0.1의 vitest 타입 확장이 vitest 5와 안 맞는다**: vitest 5의 `Assertion<R, T>`(타입 인자 2개)와 jest-dom의 `Assertion<T>`가 병합되지 않아 `toBeInTheDocument` 등이 타입에서 사라진다(런타임은 정상, `skipLibCheck`가 오류를 숨김). `src/test/jest-dom-vitest5.d.ts`로 같은 시그니처를 맞춰 덮었다 → jest-dom이 지원하면 지운다.

**검증**: `tsc`(web·types), `scripts/check.sh web --full`(vitest 480 통과), web Docker 이미지를 Node 24.21로 빌드해 컨테이너에서 페이지 응답(200) 확인, CI(Node 24).

### M1. Expo SDK 52 → 57(계획은 58)

Expo SDK는 React Native·React·expo-* 패키지 버전을 한 세트로 고정한다. 그래서 dependabot의 개별 PR(#113 #114 #120)과 #121의 모바일 React 19는 하나의 작업이다. Babel 8(#112)도 Expo 프리셋을 따라 여기서 정해진다.

1. SDK를 한 번에 하나씩 올린다(53, 54, …). 단계마다 `npx expo install --fix`로 짝 버전을 맞추고 `expo-doctor`를 통과시킨다. 앱이 작아서(TS 7개) 단계당 비용은 작다. 대신 단계를 건너뛰면 원인을 좁히기 어렵다.
2. React 19와 New Architecture 전환 시점의 경고를 정리한다.
3. 푸시 알림(`expo-notifications`)이 `device_tokens` 등록과 worker의 Expo 발송(ADR-065)과 계속 맞는지 확인한다. 토큰 형식과 권한 요청 흐름이 대상이다.

**확인 결과**(2026-10-13, **SDK 57**로 올림):
- **목표를 58이 아니라 57로 바꿨다.** SDK 58은 npm `next` 태그(베타)이고 react-native `0.88.0-rc.4`(릴리스 후보)를 묶는다. 정식(`latest`)은 57.0.28(RN 0.86.3, React 19.2.3). dependabot은 배포 태그를 보지 않아 58을 제안했다. 58이 `latest`가 되면 다시 올린다.
- **단계별(53, 54, …)이 아니라 한 번에 올렸다.** 네이티브 폴더가 없는 관리형(CNG) 앱이고 소스가 7개라 단계마다 바뀌는 것은 버전 숫자뿐이다. 대신 `expo-doctor` 21개 검사·타입 검사·iOS/Android 번들 export로 확인했다.
- 바뀐 것:
  - `expo install --fix`로 짝 버전(expo-router·notifications·device·constants 57, RN 0.86.3, React 19.2.3)을 맞췄다.
  - expo-router 57의 네이티브 피어(`expo-linking`·`react-native-safe-area-context`·`react-native-screens`·`@expo/metro-runtime`)를 직접 설치했다. 예전엔 pnpm 자동 피어로 SDK 52용이 깔려 `expo-constants` 17이 중복됐다.
  - **`react`·`react-dom`을 19.2.3으로 정확히 고정** — 범위(`~19.2.3`)면 lockfile 재생성 때 19.2.8로 올라가고, react-dom 피어가 웹의 19.3.0으로 묶여 React가 중복됐다.
  - 쓰지 않던 `@react-navigation/native` 제거(expo-router 57은 `standard-navigation`을 쓴다).
  - `app.json`의 최상위 `notification`이 스키마에서 빠져 `expo-notifications` 플러그인 옵션으로 옮겼다.
  - `setNotificationHandler`의 `shouldShowAlert`(deprecated)를 `shouldShowBanner`·`shouldShowList`(둘 다 true, 예전과 같음)로.
  - mobile-ci: Node 20 → 24, **`expo install --check` 단계 추가**(워크스페이스 lockfile이 하나라 dependabot의 react 그룹이 모바일 React를 SDK 밖으로 올릴 수 있다 — 설치된 버전이 SDK와 다르면 실패. 틀린 버전을 설치해 실패하는 것까지 확인했다), audit의 `image-size` 예외 제거(경로가 사라짐).
- **기존 문제(이번에 만든 것 아님)**: `assets/`가 비어 있어 알림 아이콘 `./assets/notification-icon.png`가 없다 — 네이티브 빌드(prebuild) 전에 넣어야 한다. 푸시 토큰은 EAS `projectId`가 없으면 등록을 건너뛴다(코드가 그렇게 돼 있다).

**검증**: `expo-doctor` 21/21, `tsc`, `expo export --platform ios --platform android`(번들 성공), mobile audit. **실기기·시뮬레이터 확인은 못 했다** — 이 맥에 Xcode가 없다(Command Line Tools만). 로그인·알림 권한·푸시 수신은 사람이 확인해야 한다.

---

## 5. 그 전까지: dependabot 설정

트랙을 시작하기 전까지 같은 메이저 PR이 매주 다시 열리지 않게 하고, 짝이 있는 의존성은 묶어 둔다. **적용됨**(2026-10-07) — `.github/dependabot.yml`의 각 항목 옆 주석에 트랙 번호가 있다:

- `ignore` (메이저): gradle — `org.springframework.boot`, `io.spring.dependency-management`, `org.springframework.modulith:*`, `org.testcontainers:*`, `net.logstash.logback:*`, `org.jetbrains.kotlin*`, `io.jsonwebtoken:*`(0.x라 minor도). npm — `next`, `eslint-config-next`, `eslint`, `tailwindcss`, `typescript`, `vitest`, `jsdom`, `@types/node`, `@babel/core`, `expo`, `expo-*`, `react-native`, `react`, `@types/react`
- `groups`:
  - `kotlin` — `jvm`, `plugin.spring`, `plugin.jpa`, `org.jetbrains.kotlin*` (jvm·spring·jpa 플러그인을 한 PR로). dependabot은 Kotlin Gradle 플러그인을 **짧은 id**로 부른다 — 처음엔 `org.jetbrains.kotlin*`만 걸어 ignore가 듣지 않았다(#140)
  - Expo 패키지(`expo-*`)와 `react-native`는 0.x라 **minor가 SDK를 바꾼다**. 그래서 minor도 ignore했다(#139가 react-native 0.76 → 0.87을 가져왔다)
  - `gradle-wrapper` 메이저 — B0
  - `jjwt` — `io.jsonwebtoken:*`
  - `react` — `react`, `react-dom`, `@types/react`, `@types/react-dom`. 워크스페이스가 lockfile 하나라 웹·모바일을 그룹으로 나눌 수 없다. 그래서 react 메이저는 ignore에 두었다(모바일 18 → 19는 M1에서, 웹은 이미 19).
  - `expo` — `expo*`, `react-native`

트랙 하나가 끝나면 그 트랙의 ignore를 지운다.

---

## 6. 진행 기록

| 트랙 | 상태 | PR | 비고 |
|---|---|---|---|
| (선행) web React 19.3 | 완료 | #129 | #121·#122를 대체. 모바일은 18 유지 |
| (선행) react-query packageExtensions 범위화 | 완료 | #117 | 고정 버전 키 때문에 타입 검사가 깨졌던 것 |
| (선행) dependabot ignore·groups | 완료 | #130 | §5 |
| B2 Testcontainers 2 | 완료 | #143 | 2.0.5. 위 확인 결과 참고 |
| B1 Kotlin 2 | 완료 | #154 | 2.3.21로 올렸고, 이후 dependabot Kotlin 그룹(minor)으로 2.4.x가 됐다(Boot 4.1 BOM은 2.3.21 — B4-5에서 Boot 플러그인이 플러그인 버전을 따르므로 문제 없음) |
| B0 Gradle 9 | 완료 | #156 | 9.8.0. wrapper만 변경 |
| B3 jjwt 0.13 | 완료 | #158 | 0.13.0. 0.12.7 토큰 호환 테스트 추가 |
| W1 Next 16 | 완료 | #160 | 16.4.0 + ESLint 9 flat config. React Compiler 린트 74곳은 warn(후속) |
| W2 Tailwind 4 | 완료 | #207 | 4.3.3. JS 설정 유지(@config), v3 기본값 호환 스타일, 스크린샷 18장 픽셀 일치 |
| W3 TS 6 · vitest 5 · Node 24 | 완료 | #208 | TS 7은 typescript-eslint 지원 대기. Node 24 LTS로 통일(web Docker의 EOL Node 20 교체) |
| M1 Expo SDK 57 | 완료 | #209 | 58은 베타라 57(정식)로. mobile-ci에 SDK 버전 검사 추가. 실기기 확인 필요 |
| B4-1 statemachine 제거 | PR | — | ADR-103. 50조합 동작 일치 |
| B4-2 Modulith v2 컬럼 | PR | — | V101, nullable 추가·backfill 불필요(소스 확인) |
| B4-3~6 | 시작 전 | — | 위 작업 단위 |
