# 백엔드 테스트 전략 — JdbcTemplate 목킹과 순수 함수 분리

311개 테스트, 38개 클래스를 거치며 정립된 패턴을 정리한다. monticker 백엔드는 시계열 집계·통계 계산이 많은 도메인이라 일반적인 Repository 목킹만으로는 부족했다.

---

## 1. 도구 선택

```kotlin
testImplementation("io.mockk:mockk:1.13.13")
testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
```

JUnit5 + MockK + AssertJ. Spring 컨텍스트를 띄우지 않고 서비스를 직접 `new`하는 순수 단위 테스트를 기본으로 한다.

```kotlin
class AlertServiceTest {
    private val repo = mockk<AlertRuleRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = AlertService(repo, ObjectMapper(), jdbc)
    ...
}
```

Spring Boot Test의 `@SpringBootTest`나 `@DataJpaTest`는 사용하지 않는다 — 컨텍스트 로딩 시간이 단위 테스트 311개 × 수 초로 누적되면 전체 빌드가 느려지고, 무엇보다 비즈니스 로직 검증에 DB 컨테이너가 필요 없기 때문이다.

---

## 2. JdbcTemplate 목킹 — SQL 문자열 부분 매칭

다수의 서비스(`RiskCheckerService`, `WalletService`, `BehaviorScoreService` 등)는 JPA가 아니라 `JdbcTemplate`으로 복잡한 집계 쿼리를 직접 실행한다. 같은 메서드 오버로드(`queryForObject(String, Class<T>, Object...)`)를 여러 다른 쿼리에서 호출하므로, 단순히 타입으로만 매칭하면 모든 호출이 같은 스텁에 걸려버린다.

```kotlin
// 잘못된 접근 — 모든 BigDecimal 쿼리가 이 스텁 하나에 매칭됨
every { jdbc.queryForObject(any<String>(), BigDecimal::class.java, any<Long>()) } returns BigDecimal.ZERO

// 실제 사용한 패턴 — SQL 본문의 고유 부분 문자열로 구분
every {
    jdbc.queryForObject(match<String> { it.contains("FROM fills") }, BigDecimal::class.java, any<Long>())
} returns dailyPnl

every {
    jdbc.queryForObject(match<String> { it.contains("paper_accounts") }, BigDecimal::class.java, any<Long>())
} returns accountCash
```

`RiskCheckerService` 하나에 동일 시그니처의 쿼리가 5개 이상 섞여 있었는데, 각 쿼리가 참조하는 테이블명이나 고유 컬럼 조합(`"GROUP BY stock_id HAVING"`, `"AND stock_id = ?"` 등)을 골라 매칭 문자열로 사용했다. 이 방식의 단점은 SQL 리팩터링 시 테스트도 함께 깨질 수 있다는 점이지만, 실제 동작을 정확히 검증하려면 불가피한 트레이드오프다.

### `@BeforeEach`로 "안전한 기본값" 세팅

```kotlin
@BeforeEach
fun setUp() {
    mockDailyPnl(BigDecimal.ZERO)
    mockAccountCash(BigDecimal("10000000"))
    mockHoldings(emptyList())
    mockVarStockIds(emptyList())
    mockPositionCount(0L)
    mockIsNewStock(0)
    mockHourlyOrders(0L)
}
```

5개 규칙을 가진 `RiskCheckerService`처럼 여러 쿼리가 동시에 필요한 서비스는, 모든 규칙이 통과하는 "중립" 상태를 기본값으로 깔아두고 개별 테스트에서 검증하려는 규칙 하나만 `every { ... }`로 덮어쓴다. 이렇게 하면 "이 테스트가 실제로 검증하는 조건"이 코드에서 즉시 드러난다.

---

## 3. 순수 함수는 목킹 없이 직접 검증

`PortfolioOptimizerService.minimizeVariance()`, `PatternRecognizerService.zigZag()`, `RegimeDetectorService.calculateADX()` 같은 수치 계산 함수는 `JdbcTemplate` 의존성이 전혀 없다. Spring `@Service`의 일반 메서드로 선언되어 있지만 입력→출력만으로 완결되므로, 서비스 객체를 생성한 뒤 메서드를 직접 호출해 목킹 없이 검증한다.

```kotlin
@Test
fun `minimizeVariance favours the lower-variance asset when returns differ`() {
    val cov = arrayOf(doubleArrayOf(0.01, 0.0), doubleArrayOf(0.0, 0.0001))
    val mu = doubleArrayOf(0.001, 0.001)
    val weights = service.minimizeVariance(cov, mu, targetReturn = 0.001)
    assertThat(weights[1]).isGreaterThan(weights[0])   // 분산이 작은 자산에 더 큰 비중
}
```

이런 함수는 `jdbc` 의존성을 가진 다른 메서드(`optimize()`, `detectPatterns()`)와 같은 클래스에 있더라도, 해당 메서드만 호출하면 `jdbc` 목이 전혀 호출되지 않으므로 스텁 설정 없이도 컴파일·실행된다. **클래스 단위가 아니라 메서드 단위로 "이 테스트가 무엇에 의존하는가"를 판단**하는 것이 핵심이다.

---

## 4. BigDecimal 비교 — `isEqualByComparingTo`

```kotlin
// 틀린 방식: equals()는 scale까지 비교하므로 100.0 != 100.00
assertThat(result).isEqualTo(BigDecimal("100.00"))

// 올바른 방식: 수치적으로 동일한지만 비교
assertThat(result).isEqualByComparingTo(BigDecimal("100"))
```

`BigDecimal.equals()`는 `compareTo() == 0`과 다르게 scale(소수 자릿수)까지 같아야 `true`다. 금액·비율 계산 결과의 scale은 연산 경로에 따라 달라지므로(나눗셈의 `setScale`, 곱셈의 자동 scale 합산 등), 전체 테스트 스위트에서 `isEqualByComparingTo`를 표준으로 사용했다.

---

## 5. 행동 발견 → 테스트 설계 수정 (계획대로 되지 않은 사례)

`QuantBacktestEngineTest` 작성 중 "진입 조건이 항상 참인 룰"을 만들려고 `PROFIT_RATE GTE -999`를 매수 조건으로 사용했으나, 전부 거래가 0건으로 나왔다. 원인은 `RuleEvaluator.evaluateEntryCondition()`이 `PROFIT_RATE`/`LOSS_RATE`를 인식하지 못하고 `else -> false`로 떨어지기 때문이었다(`quant-rule-engine.md` 참고). 진입 조건에는 `CLOSE_VS_MA(period=1)` — 자기 자신과 비교하므로 항상 참 — 를 대신 사용해 의도한 시나리오를 만들었다.

```kotlin
// "항상 참인 진입 조건"을 만드는 올바른 방법
private fun alwaysTrueEntryCondition() =
    RuleCondition(indicator = "CLOSE_VS_MA", comparator = "GTE", params = mapOf("period" to 1))
    // MA(period=1)는 자기 자신의 종가와 같으므로 close >= MA(1)은 항상 참
```

이런 시행착오는 **테스트가 실제 코드 동작에 대한 잘못된 가정을 빠르게 드러낸다**는 점을 보여준다. 처음 작성한 테스트가 그대로 통과했다면 오히려 `RuleEvaluator`의 진입/청산 지표 비대칭성을 놓쳤을 것이다.

---

## 6. 발견한 두 가지 실제 버그

| 버그 | 원인 | 발견 경로 |
|------|------|-----------|
| `OrderBook.getBestBid()/getBestAsk()`가 빈 호가창에서 예외 발생 | `firstKey()`가 `takeIf` 가드보다 먼저 평가됨 | "빈 호가창" 엣지케이스 테스트 작성 중 |
| `WatchlistControllerTest` 3개 전부 실패 | standalone `MockMvc`에 Spring Security 필터 체인이 없어 `@AuthenticationPrincipal`을 해석 못함 | 전체 스위트 클린 빌드 시 |

두 번째 사례는 `HandlerMethodArgumentResolver`를 직접 등록해 해결했다.

```kotlin
private val authPrincipalResolver = object : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) =
        parameter.parameterType == Long::class.java &&
            parameter.hasParameterAnnotation(AuthenticationPrincipal::class.java)
    override fun resolveArgument(...): Any = 1L   // 고정 테스트 사용자 ID
}

MockMvcBuilders.standaloneSetup(controller)
    .setCustomArgumentResolvers(authPrincipalResolver)
    .build()
```

`@AuthenticationPrincipal`을 쓰는 컨트롤러를 standalone MockMvc로 테스트할 때 공통으로 필요한 패턴이며, 향후 동일 어노테이션을 쓰는 새 컨트롤러 테스트에 재사용할 수 있다.
