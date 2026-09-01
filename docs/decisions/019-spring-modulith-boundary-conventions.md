# ADR-019: Spring Modulith 모듈 경계 규칙 확립

## Status
Accepted

## Context

`ModulithStructureTest`(`ApplicationModules.of(ApiApplication::class.java).verify()`)가 150개 이상의
`Violations`로 실패하고 있었다. 기능이 추가되는 동안 각 모듈의 `package-info.java`
`allowedDependencies` 선언이 실제 코드의 모듈 간 참조를 따라가지 못한 채 방치된 결과다.

크게 세 종류의 문제가 섞여 있었다:

1. **진짜 순환 의존**: `common.aop.RiskCheckedAspect`가 `matching.application.RiskCheckerService`를
   호출하는데, `matching`도 `common.domain.Money`/`Price` 등을 사용해 `common ↔ matching` 순환이
   발생했다. 같은 이유로 `wallet`이 `paper`의 계좌·거래 조회 서비스를 호출하고 `paper`가 다시
   `wallet.application.LedgerService`를 직접 호출해 `wallet ↔ paper` 순환도 있었다.
2. **선언 누락**: `allowedDependencies`가 아예 비어 있거나(`{}`) 실제로 필요한 모듈이 빠져 있어서,
   기능은 동작하지만 Modulith가 위반으로 잡는 경우.
3. **경계 침범**: `watchlist.application.WatchlistService`가 `stock.infrastructure.StockRepository`를
   직접 호출하는 등, ADR-001이 명시한 "모듈이 서로의 리포지토리를 직접 호출하지 않는다" 원칙이
   깨진 경우.

## Decision

### 1. `common`은 OPEN 공유 커널로 선언한다

`common`(도메인 값 객체, AOP 애노테이션, tracing, 설정)은 어떤 비즈니스 모듈도 되돌아 참조하지
않는 진짜 공유 커널이다. `@ApplicationModule(type = Type.OPEN, allowedDependencies = {})`로
선언해 하위 패키지(`common.aop`, `common.domain`, `common.tracing` 등)를 전부 노출한다.

### 2. 순환은 "잘못 놓인 코드"를 원래 모듈로 옮겨서 끊는다

- `RiskCheckedAspect`는 `matching.application.RiskCheckerService`에만 의존하는 `matching` 내부
  관심사였다. `common.aop` → `matching.aop`로 이동시켜 `common → matching` 방향을 완전히 없앴다.
  `@RiskChecked`/`@RiskParam`/`RiskLimitException`은 여러 모듈이 참조하는 진짜 공유 계약이므로
  `common.aop`에 남긴다.
- `SecurityConfig`(JWT 필터, OAuth2 핸들러 조립)는 `auth`의 관심사인데 `common.config`에 있었다.
  `auth.config`로 이동시켰다.
- `paper ↔ wallet`은 다른 성격이라 이동으로 끊을 수 없었다(§3 참조).

### 3. 모듈 간 재정산(ledger) 기록은 이벤트로, 조회는 서비스 호출로 분리한다

`PaperTradingService.buy/sell`, `PaperSettlementService.settle`이 체결·정산 직후
`wallet.application.LedgerService`를 직접 호출했다. 반대로 `wallet`의 `EmotionTagService`,
`ReceiptService`, `WalletService`는 페이퍼 거래·계좌를 읽어야 한다. 양방향 참조가 그대로
`paper ↔ wallet` 순환이 되었다.

- **조회(paper → wallet 방향 없음)**: `paper.application`에 `PaperTradeQueryService`,
  `PaperAccountQueryService`를 새로 만들어 `wallet`이 리포지토리 대신 이 서비스를 호출하게 했다.
- **기록(wallet → paper 방향 없음)**: `paper`는 `LedgerService`를 직접 부르지 않고
  `PaperTradeExecutedEvent`/`PaperSettlementCompletedEvent`를 발행한다. `wallet`의 새 리스너
  `PaperTradeEventListener`(`@ApplicationModuleListener`)가 이를 구독해 `LedgerService`를 호출한다.
  이는 이미 ADR-008/011에서 확립된 "matching이 이벤트를 발행하고 wallet의
  `OrderFilledEventListener`가 구독해 원장을 기록한다" 패턴을 페이퍼 트레이딩에도 그대로 적용한
  것이다.

결과적으로 `paper → wallet` 의존은 완전히 제거되고 `wallet → paper`(조회 서비스 + 이벤트 구독)
한 방향만 남는다.

### 4. 모듈의 공개 API는 `@NamedInterface`로 명시한다

Spring Modulith 기본 규칙상 모듈의 루트 패키지만 노출되고 `application`/`domain`/`infrastructure`
하위 패키지는 비공개다. 여러 모듈이 서비스 계층이나 도메인 타입을 합법적으로 참조해야 하므로
`@NamedInterface`로 그 경계를 명시적으로 열었다.

- **패키지 단위**: `stock.application`("api"), `stock.domain`("domain")처럼 계층별로 이름을
  붙인다. **한 모듈 안에서 서로 다른 패키지에 같은 이름을 재사용하면 안 된다** — 실제로
  `stock.application`과 `stock.domain`에 똑같이 `"api"`를 붙였더니 하나만 등록되고 나머지는
  조용히 무시되는 현상을 확인했다(Spring Modulith 1.3.4). 패키지마다 고유한 이름을 쓴다
  (`api`, `domain`, `events`, `pg` 등).
- **타입 단위**: 패키지 전체를 열 필요 없이 특정 클래스 하나만 공개할 때는 클래스에 직접
  `@NamedInterface`를 붙인다(`JwtTokenProvider`, `LedgerService`, `BehaviorScoreService`,
  `CreatorEarningsService`). 같은 패키지 안에서 여러 타입이 같은 이름을 공유하는 것은 문제없이
  병합된다 — 충돌은 "패키지 대 패키지"일 때만 발생했다.
- 리포지토리(`infrastructure`)는 원칙적으로 노출하지 않는다. 다른 모듈은 항상 `application`
  계층의 서비스를 거쳐야 한다(§5의 배치 잡 예외 제외).

### 5. Spring Batch의 `RepositoryItemReader`는 예외적으로 리포지토리 직접 접근을 허용한다

`RepositoryItemReaderBuilder.repository(...)`는 Spring Data 리포지토리 빈을 직접 요구하는
프레임워크 제약이라 서비스 계층으로 감쌀 수 없다. `batch` 모듈이 필요로 하는 리포지토리만
`"batch"`라는 이름의 `@NamedInterface`로 별도 노출해 이 예외를 코드로 명시했다
(`brokerage.infrastructure`, `paper.infrastructure`, `subscription.infrastructure`).
그 외 batch가 필요로 하는 서비스·도메인 타입은 다른 모듈과 동일하게 `application`/`domain`
NamedInterface를 재사용한다.

### 6. 리포지토리 직접 참조는 서비스 호출로 리팩터링한다

- `watchlist.application.WatchlistService`가 `stock.infrastructure.StockRepository.findById`를
  직접 호출하던 것을 기존에 있던 `stock.application.StockService.getById()`로 교체했다.
- `analytics.application.PositionSizerService`가 `quant.infrastructure.QuantBacktestResultRepository`를
  직접 호출하던 것을, `quant.application.RuleSetService`에 새로 추가한
  `getLatestBacktestResult(ruleSetId)` 조회 메서드로 교체했다(기존 `listBacktestResults`는
  `userId` 소유권 검증이 필요해 시그니처가 달라 재사용할 수 없었다).

## Reasons

- 순환 의존을 이벤트로 끊는 방식은 이미 ADR-008(Outbox)·ADR-011(Saga)이 `matching → wallet`
  관계에서 확립한 패턴이다. 같은 패턴을 `paper → wallet`에도 적용해 일관성을 유지했다.
- `@NamedInterface`는 파일을 옮기지 않고도 경계를 선언할 수 있어, 대규모로 흩어진 위반을 안전하게
  정리하는 데 가장 리스크가 낮은 도구였다.
- 리포지토리 대신 서비스를 거치게 하는 것은 ADR-001이 처음부터 요구한 규율이다. 이번 정리는
  새 규칙이 아니라 방치됐던 기존 규칙의 재적용이다.

## Consequences

- `package-info.java`가 모듈 수만큼 늘었고, `allowedDependencies`에 `module::interfaceName`
  형태의 참조가 많아져 가독성이 다소 떨어진다. 새 모듈 간 의존을 추가할 때는 대상 타입이 이미
  노출된 NamedInterface에 속하는지 먼저 확인해야 한다.
- `batch` 모듈은 여러 모듈의 `infrastructure` 계층까지 볼 수 있는 유일한 예외 모듈이 되었다.
  이 예외 범위를 넓힐 때는(새 배치 잡 추가 등) 정말 `RepositoryItemReader` 때문인지 확인해야 한다
  — 그렇지 않다면 서비스 계층을 거치는 원칙을 따른다.
- `PaperTradeEventListener`는 `@ApplicationModuleListener`라 커밋 이후 비동기로 실행된다.
  기존 `LedgerService.recordBuy/recordSell` 직접 호출은 같은 트랜잭션 안에서 즉시 실행됐던
  것과 타이밍이 달라졌다 — 잔고(`balanceAfter`)는 이벤트 페이로드에 스냅샷으로 담아 넘기므로
  값 자체는 정확하지만, 원장 기록이 거래 응답 반환 시점보다 늦게 완료될 수 있다.

## Revisit When

- `@NamedInterface` 선언이 모듈당 4~5개를 넘어 관리가 어려워지면, 모듈을 더 작은 단위로
  쪼개는 것을 검토한다.
- `batch`가 지금보다 더 많은 모듈의 `infrastructure`를 필요로 하게 되면, Spring Batch의
  `ItemReader`를 각 모듈이 직접 구현해 제공하는 방식(현재의 예외적 노출 대신)으로 전환을
  검토한다.
