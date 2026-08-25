# ADR-015: @ConditionalOnProperty for Mock/Real Client Switching

## Status
Accepted

## Context

monticker는 외부 의존 시스템이 두 개 있다:

1. **PG (Payment Gateway)**: Toss Payments — 실제 결제 처리
2. **Brokerage (증권사)**: KIS Open API — 실제 주문 체결

개발/테스트 환경에서는 실제 API를 호출하면 안 된다:
- 실제 결제가 발생할 수 있다
- KIS API는 운영 계정 승인이 필요하다
- 네트워크 의존성으로 CI/CD가 불안정해진다

구현체를 어떻게 전환할 것인가:

**A) 환경 변수로 직접 분기** (`if (mockEnabled) {...}`)
- 한 클래스에 Mock/Real 로직이 혼재. 단위 테스트 어려움.

**B) 별도 구현체 + 런타임 주입**
- `PgClient` 인터페이스 → `MockPgClient` / `TossPgClient` 두 구현체
- 스프링 빈 조건부 등록으로 환경에 따라 자동 선택

## Decision

**`@ConditionalOnProperty` 기반 구현체 분리** 방식을 채택한다.

```kotlin
// Mock (기본: 개발/테스트용)
@Component
@ConditionalOnProperty("app.pg.mock.enabled", havingValue = "true", matchIfMissing = true)
class MockPgClient : PgClient { ... }

// Real (운영용)
@Component
@ConditionalOnProperty("app.pg.mock.enabled", havingValue = "false")
class TossPgClient(@Value("\${app.toss.secret-key}") ...) : PgClient { ... }
```

`application.yml`: `app.pg.mock.enabled: true` (기본)  
`application-prod.yml`: `app.pg.mock.enabled: false` (운영)

동일 패턴을 `BrokerageClient`에도 적용한다:
- `MockBrokerageClient` ↔ `KisBrokerageClient`

추가로 `app.ai.mock.enabled`도 동일 패턴을 따른다.

## Reasons

- **관심사 분리**: Mock 로직이 Real 구현체를 오염시키지 않는다. 각 클래스가 단일 책임.
- **테스트 용이성**: Mock 구현체는 `@SpringBootTest` 없이도 단위 테스트 가능.
- **안전한 기본값**: `matchIfMissing = true`로 설정 누락 시 Mock이 활성화. 운영에서는 명시적으로 `false`를 설정해야 함.
- **컴파일 타임 인터페이스 보장**: 두 구현체가 동일 인터페이스를 구현하므로 API 불일치 불가.
- **스프링 프로파일 대안**: `@Profile("prod")`도 가능하지만, 하나의 프로파일에서 Mock/Real을 세밀하게 제어하기 어렵다.

## Consequences

- **설정 누락 위험**: 운영 배포 시 `app.pg.mock.enabled=false` 설정을 빠뜨리면 Mock이 활성화되어 결제가 처리되지 않는다. `application-prod.yml`에 명시하고 배포 체크리스트에 포함.
- **Real 구현체 테스트**: `KisBrokerageClient`의 통합 테스트는 KIS VTS(모의투자 서버) 환경에서만 가능.
- **시크릿 관리**: Real 구현체는 API 키/시크릿을 환경 변수로 주입. 코드에 하드코딩 절대 금지.
- **운영 안전장치**: `application-prod.yml`에 `*_MOCK_ENABLED: false`를 명시. CI에서 prod 프로파일 시 Mock=true 설정 금지 규칙 추가 검토.

## Revisit When

- 외부 의존 시스템이 늘어날 때 → 동일 패턴 일관 적용.
- 더 정교한 Contract Testing(Pact 등)이 필요할 때 → Mock 구현체를 Consumer-Driven Contract Test로 교체.
