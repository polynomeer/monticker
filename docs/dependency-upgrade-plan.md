# 메이저 의존성 업그레이드 계획

> Read this when: 2026-10-07에 닫은 dependabot 메이저 PR(#101~#104, #106, #108, #109, #112~#114, #120~#123)을 다시 올릴 때. 순서와 묶음을 정해 둔 문서다.

dependabot은 의존성마다 PR을 하나씩 연다. 메이저는 대개 **짝이 있는 의존성과 함께**, **선행 조건을 갖춘 뒤**에 올려야 해서 하나씩은 받을 수 없었다(닫은 사유는 각 PR 코멘트에 있다). 이 문서는 그 묶음을 작업 단위(트랙)로 다시 짠다.

버전별 변경 사항은 이 문서를 쓴 시점의 이해다. **각 트랙의 0단계는 공식 마이그레이션 가이드를 읽고 아래 "확인할 것"을 사실로 바꾸는 일이다.** 확인한 내용이 다르면 이 문서를 고친다.

---

## 1. 한눈에

| 트랙 | 대상 | 닫은 PR | 선행 조건 | 크기 | 위험 |
|---|---|---|---|---|---|
| **B0** Gradle 9 | Gradle wrapper 8.14 → 9.x (api·worker) | #135 #138 | 없음 | 소~중 | 중 — 빌드 스크립트·플러그인 호환 |
| **B1** Kotlin 2 | `kotlin("jvm"/"plugin.spring"/"plugin.jpa")` 1.9.25 → 2.x (api·worker) | #106 #108 #109 | 없음 | 중 | 중 — K2 컴파일러가 686개 파일을 다시 본다 |
| **B2** Testcontainers 2 | `testcontainers-bom` 1.20 → 2.x | #101 | 없음 | 소 | 하 — 테스트 전용, 8개 파일 |
| **B3** jjwt 0.13 | `jjwt-api`·`jjwt-impl`·`jjwt-jackson` 0.12.6 → 0.13 | #102 | 없음 | 소 | **상** — 인증 경로 |
| **B4** Spring Boot 4 | Boot 3.5 → 4.x + Modulith 2 + logstash-logback-encoder 9 | #103 #104 | **B1**(Kotlin 2.2+), B2 권장 | **대** | **상** — 실거래 경로 전체 |
| **W1** Next 16 | `next`·`eslint-config-next` 15 → 16, ESLint flat config | #123, #112 일부 | 없음 | 중 | 중 |
| **W2** Tailwind 4 | `tailwindcss` 3 → 4 | #112 일부 | W1 권장 | 중 | 중 — 화면 전체의 시각 회귀 |
| **W3** 툴체인 | TypeScript 7, vitest 5, jsdom 30, @types/node | #112 일부 | W1 | 소~중 | 하 |
| **M1** Expo SDK | Expo 52 → 58 (expo-router·expo-notifications·RN·React 19·Babel 8) | #113 #114 #120, #121 모바일분 | 없음 | 중 | 중 — 앱은 작지만(TS 7개) 6개 SDK를 건넌다 |

**권장 순서**: B2 → B0 → B1 → B3 → (W1 → W2 → W3, M1은 병렬) → B4. 백엔드 트랙은 실거래 경로에 걸리므로 한 번에 하나씩, 각각 독립 PR로 머지하고 며칠 운영(또는 로컬 장시간 기동)을 거친 뒤 다음으로 간다. 웹·모바일 트랙은 백엔드와 독립이라 병렬로 진행해도 된다.

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

**확인할 것**: Spring Boot 3.5 Gradle 플러그인과 Kotlin 1.9.25 플러그인의 Gradle 9 지원 여부. 지원하지 않으면 B1(Kotlin 2) 뒤로 미룬다.
**검증**: api·worker `test`·`integrationTest`, `bootJar`·Docker 이미지 빌드(deploy-images).

### B1. Kotlin 1.9 → 2.x

Kotlin 컴파일러 플러그인(`plugin.spring`·`plugin.jpa`)은 `kotlin("jvm")`과 **반드시 같은 버전**이다. dependabot은 이 셋을 따로 올려 버전이 어긋났다(#106 #108 #109). Boot 4가 Kotlin 2.x를 요구하므로 B4의 선행 조건이기도 하다. Boot 3.5에서 먼저 올려 두면 B4의 변경 범위가 줄어든다.

1. api·worker의 세 플러그인을 같은 버전으로 올린다. 가능하면 Boot 4가 기준으로 삼는 Kotlin 버전에 맞춘다.
2. K2 컴파일러가 새로 내는 경고와 오류를 정리한다. 스마트 캐스트, 오버로드 해석, `@JvmStatic`·`companion` 관련이 흔하다.
3. `kotlin-reflect`와 `jackson-module-kotlin`이 새 Kotlin과 호환되는지 본다. 그 위에서 data class 역직렬화(Kafka 메시지, API 요청 DTO)가 그대로 동작하는지 테스트로 확인한다.
4. `freeCompilerArgs`(`-Xjsr305=strict`)가 그대로 유효한지 확인한다.

**확인할 것**: Boot 3.5가 공식 지원하는 Kotlin 최고 버전. `allOpen`/`noArg` 설정이 K2에서 같은 동작인지.
**검증**: api·worker `test` + `integrationTest`. `ModulithStructureTest`(모듈 경계)가 통과해야 한다. `JsonbColumnMappingTest`처럼 리플렉션으로 엔티티를 훑는 테스트에 주의한다.

### B2. Testcontainers 1.20 → 2.x

테스트 전용이라 가장 안전한 메이저다. 다른 트랙을 시작하기 전에 끝내 두면 이후 트랙의 통합 테스트가 새 버전 위에서 돈다.

1. BOM을 올리고 컴파일 오류를 따라간다. 쓰는 범위는 `PostgreSQLContainer`·`GenericContainer`·`DockerImageName`과 JUnit5 확장(`@Testcontainers`·`@Container`)으로, 8개 파일이다.
2. 공유 컨테이너 베이스(`PostgresIntegrationTest`)의 "직접 start(), stop() 안 함" 관례는 유지한다. 이 클래스 주석에 적힌 컨테이너 공유 레이스 때문이다.

**확인할 것**: 모듈 아티팩트 이름과 패키지가 바뀌었는지(예: postgres 모듈 분리). JUnit4 지원 제거가 우리와 무관한지.
**검증**: api·worker `integrationTest` 전부.

### B3. jjwt 0.12 → 0.13

`jjwt-api`만 올리면 런타임 모듈(`jjwt-impl`·`jjwt-jackson`)과 버전이 어긋난다(#102). **세 아티팩트를 함께 올린다.** 사용처는 `JwtTokenProvider` 한 곳이지만, 모든 인증이 이 경로를 지난다.

1. 세 아티팩트를 같은 버전으로 올린다.
2. 서명·검증 API의 deprecated·제거 항목을 따라간다.
3. **이전 버전이 발급한 토큰을 새 버전이 검증하는지** 테스트로 고정한다. 배포 직후 로그인된 사용자가 전부 로그아웃되면 안 된다. 리프레시 토큰도 같다.

**확인할 것**: 0.13의 breaking change(키 길이 검사 강화 여부 등). `InsecureSecretGuard`가 쓰는 개발용 키가 새 검사를 통과하는지.
**검증**: 인증 단위·통합 테스트, e2e 로그인 흐름. 0.12로 만든 토큰을 픽스처로 남겨 검증하는 테스트를 추가한다.

### B4. Spring Boot 3.5 → 4.x

가장 큰 트랙이다. Boot 4는 Spring Framework 7 위에 있고, 그 생태계(Data, Batch, Kafka, Security, Modulith, Integration)가 함께 메이저를 올린다. 실거래 주문 경로(ADR-055~063)가 전부 이 위에 있으므로 **B1·B2·B3를 끝내고 시작한다.**

0. **영향 조사 (코드 변경 전).** 아래 "확인할 것"을 하나씩 사실로 바꾸고, 막히는 의존성이 있으면 여기서 멈춘다.
1. **막히는 의존성부터 해결한다.**
   - **Spring Statemachine 4.0.0**: 주문 상태 머신(`matching/statemachine/OrderStateMachineConfig`)이 쓴다. Framework 7을 지원하는 릴리스가 없으면, 직접 구현으로 바꾸는 별도 PR을 먼저 머지한다. 상태 전이 표가 작아서 가능할 것으로 본다. 이 경우 대안 비교를 ADR로 남긴다.
   - **springdoc-openapi 2.5.0**: Boot 4 대응 메이저가 필요하다. 코드 사용처(어노테이션)는 0곳이니, 쓰지 않으면 제거하는 것도 선택지다.
   - **spring-retry**: 10개 파일이 쓴다. Framework 7에 들어온 재시도 기능으로 옮길지, 그대로 둘지 결정한다.
   - **Modulith 1.3 → 2.x**: Boot 4 짝 버전이다. `event_publication` 스키마 변경 여부를 확인한다. 아웃박스(ADR-042·065)가 이 테이블에 의존하고, 바뀌면 Flyway 마이그레이션이 필요하다.
2. **Jackson 2 → 3.** Boot 4의 기본 JSON이 Jackson 3라면, 직접 Jackson을 쓰는 60개 파일(`com.fasterxml.jackson` → `tools.jackson`)이 영향을 받는다. 전부 옮길지, Jackson 2 호환 모드로 먼저 올리고 나중에 옮길지 정한다(**ADR**). 어느 쪽이든 **와이어 포맷은 바뀌면 안 된다.** Kafka 메시지(`notify.user`, `search.index`, `trading.*`), Redis 캐시, API 응답이 대상이다. 직렬화 결과를 고정하는 테스트를 먼저 추가한다.
3. **스타터 재편·설정 키 변경**을 따라간다. `application.yml`의 바뀐 키는 Boot의 properties migrator로 찾는다.
4. **Spring Batch 6.** 잡 저장소 스키마가 바뀌는지 확인한다. 정산·결제 청소·구독 갱신 잡과 `KeysetItemReader`의 `ItemStreamReader` 계약이 대상이다.
5. **logstash-logback-encoder 9** (#104)를 함께 올린다. Jackson 3에 맞춘 버전일 가능성이 높아 B4와 묶었다. `logback-spring.xml`의 운영 로그 필드 이름이 바뀌지 않는지 본다. 로그 기반 알람과 대시보드가 필드 이름에 의존한다.
6. Hibernate 7 / Spring Data의 쿼리 파생·`@Query` 동작 차이. `JsonbEntityPersistenceIntegrationTest`처럼 실제 DB로 매핑을 보는 테스트가 기준이다.

**확인할 것**: Boot 4가 요구하는 Kotlin·Java 최저 버전(현재 Java 21이라 문제없을 것으로 본다). Jackson 3 기본 여부와 Jackson 2 호환 수단. Spring Statemachine·springdoc의 Boot 4 지원 버전. Modulith 2의 `event_publication` 스키마. Batch 6의 메타데이터 스키마. Elasticsearch 클라이언트 버전 변경 여부(2026-10-05에 서버를 클라이언트 8.18.1에 맞췄다. 클라이언트가 9.x로 가면 서버도 함께 올린다, docs/elasticsearch.md).
**검증**:
- `scripts/check.sh --full`, e2e-ci
- **L-05 order-burst + 정합성 검증**(`bench/scenarios/order-burst.js`, `bench/consistency/verify.py`). 원장 드리프트 0을 유지해야 한다.
- 카오스 CH-05(Kafka 정지)와 CH-13(PG 정지)로 아웃박스 재전송과 결과 불명 주문(ADR-056) 경로를 다시 확인한다.
- 직렬화 고정 테스트 통과(와이어 포맷 불변).
- 운영 배포는 카나리 → 전체. 킬 스위치(ADR-057) 런북을 준비해 둔다.

---

## 4. 웹·모바일 트랙

### W1. Next 15 → 16 (+ ESLint flat config)

`next`와 `eslint-config-next`는 같은 메이저로 움직인다. #112에 섞여 있던 `eslint-config-next 16`과 ESLint 메이저가 여기에 붙는다.

1. `next`·`eslint-config-next`를 함께 올린다. React는 19.3으로 이미 올렸다(#129).
2. `next lint`는 이미 deprecated 경고가 나고 있다. ESLint CLI로 옮긴다(`.eslintrc.json` → `eslint.config.mjs` flat config). `package.json`의 `lint` 스크립트와 web-ci도 함께 바꾼다.
3. 요청 API(`cookies()`·`headers()`·`params`)의 비동기 전용 전환, 미들웨어 관련 변경, 기본 번들러 변경을 따라간다.
4. 루트 `package.json`의 `packageExtensions`(`next@15.5.25` 키)를 새 버전에 맞춘다. 고정 버전 키 때문에 react-query 5.104에서 타입 검사가 깨진 전례가 있다(#117). 범위 키로 바꿔 둔다.

**확인할 것**: Node 최저 버전(CI web은 22, mobile은 20), `next.config.ts` 옵션 이름 변경, CSP(Pretendard self-host)에 영향을 주는 헤더·폰트 처리.
**검증**: `scripts/check.sh web --full`, e2e-ci, 화면 캡처(`apps/web/scripts/capture-screenshots.mjs`)를 업그레이드 전후로 찍어 비교한다.

### W2. Tailwind 3 → 4

설정 방식이 바뀌는 메이저다(JS 설정 → CSS 우선, PostCSS 플러그인 분리, autoprefixer 불필요). 디자인 토큰(Dracula 팔레트, 터미널 디자인 ADR-066)이 `tailwind.config.ts`(106줄)에 있어서 **화면 전체의 시각 회귀**가 위험이다.

1. 공식 업그레이드 도구로 설정을 변환하고, 토큰이 같은 값으로 옮겨졌는지 diff로 확인한다.
2. `postcss.config.mjs`를 갱신하고 `autoprefixer`를 제거한다.
3. 다크 모드 전략(`next-themes`, class 기반)이 그대로 동작하는지 확인한다.

**검증**: 캡처 스크립트로 전 화면을 전후 비교한다(라이트·다크). e2e-ci도 함께 본다.

### W3. TypeScript 7 · vitest 5 · jsdom 30 · @types/node

1. **@types/node는 런타임 Node 메이저에 맞춘다.** dependabot이 제안한 26은 CI(Node 22)·Docker 런타임과 맞지 않는다. Node 런타임을 올릴 때 함께 올린다.
2. **TypeScript 7**: 제거된 옵션(`tsconfig.json`)과 `next build`의 타입 검사 호환을 확인한다. 모바일(`typescript ~5.3`)은 M1에서 Expo가 요구하는 버전을 따른다.
3. vitest·jsdom: 설정 경고(`vitest.config.ts` ESM 경고가 이미 나고 있다)를 정리하고 올린다.

**검증**: `scripts/check.sh web --full`.

### M1. Expo SDK 52 → 58

Expo SDK는 React Native·React·expo-* 패키지 버전을 한 세트로 고정한다. 그래서 dependabot의 개별 PR(#113 #114 #120)과 #121의 모바일 React 19는 하나의 작업이다. Babel 8(#112)도 Expo 프리셋을 따라 여기서 정해진다.

1. SDK를 한 번에 하나씩 올린다(53, 54, …). 단계마다 `npx expo install --fix`로 짝 버전을 맞추고 `expo-doctor`를 통과시킨다. 앱이 작아서(TS 7개) 단계당 비용은 작다. 대신 단계를 건너뛰면 원인을 좁히기 어렵다.
2. React 19와 New Architecture 전환 시점의 경고를 정리한다.
3. 푸시 알림(`expo-notifications`)이 `device_tokens` 등록과 worker의 Expo 발송(ADR-065)과 계속 맞는지 확인한다. 토큰 형식과 권한 요청 흐름이 대상이다.

**확인할 것**: 각 SDK의 RN·React 버전과 New Architecture 의무화 시점. 최소 iOS·Android 버전 변경(스토어 배포 영향).
**검증**: mobile-ci(typecheck, audit). 시뮬레이터에서 로그인, 알림 권한, 푸시 수신을 수동으로 확인한다.

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
| B1~B4, W1~W3, M1 | 시작 전 | — | |
