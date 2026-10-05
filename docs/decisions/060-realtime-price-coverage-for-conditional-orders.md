# ADR-060: 조건부 주문은 실시세가 흐르는 종목에만 — 커버리지 공표, 생성 시 거부, 상실 감시, Mock 증권사 예외

## Status
Accepted

[ADR-055](055-price-provenance-gate-for-real-orders.md)(실시세 출처 게이트)의 후속이다. ADR-055의 "실시세로만 발동" 규칙은 그대로 두고,
**발동할 수 없는 조건부 주문이 만들어지거나 조용히 남아 있는 문제**를 다룬다. ADR-055의 게이트를 "연결된 증권사가 실제 돈을
움직일 때만" 적용하도록 좁힌다(§4).

## Context

ADR-055 이후 조건부 주문은 실시세(KIS·Toss) 틱으로만 발동한다. 그런데:

1. **사용자는 자기 종목이 실시세 대상인지 모른다.** 커버리지 밖 종목에 스탑로스를 걸면 생성은 성공하고, 발동은 영영 안 된다.
   사용자는 보호받고 있다고 믿는다. 가짜 가격에 발동하던 것(ADR-055 이전)보다는 안전하지만, 이건 별개의 위험이다.
2. **커버리지는 worker가 기동할 때 한 번 계산하는 정적 집합이고, 사용자와 무관하게 정해진다.**
   `KisCoverageProvider`는 `ORDER BY id LIMIT 21` — ID가 작은 국내 21종목. Toss는 미국 전체 + 국내 나머지 최대 100.
   ID가 더 작은 종목이 추가되면 집합이 밀려 **기존에 커버되던 종목이 빠진다.** api는 이 집합을 모른다(다른 JVM).
3. **기본 배포 설정(`infra/k8s/base/configmap.yaml`)은 `INGESTION_SOURCE: "internal"` — 커버리지 0종목이다.**
   즉 지금 기본 배포에서 **모든 실계좌 조건부 주문은 발동하지 않는다.** 아무도 모른 채로.
4. **커버리지에 있어도 실시세가 끊길 수 있다.** KIS 웹소켓 단절 시 Mock으로 대체되지 않고 틱이 멈춘다(2026-10 설계 리뷰 T4).
5. **개발 환경에서는 조건부 주문을 시험할 수 없다.** `app.brokerage.mock.enabled=true`면 KIS·Toss 계좌도 전부 Mock 증권사로
   가서 실제 돈이 움직이지 않는데, ADR-055는 계좌 종류와 무관하게 합성 틱을 막는다.

고려한 대안:
- **api가 worker의 커버리지 계산을 복제** — 같은 SQL을 api에도 둔다. 둘이 어긋나는 순간(설정이 다르거나 기동 시점이 다르면)
  같은 사고가 다시 난다. ADR-055에서 이미 기각한 방식이다.
- **관측만으로 판정** — api가 받는 틱의 출처로 "최근 실시세가 온 종목"을 센다. 장 마감·주말·api 재기동 직후에는 아무것도
  모른다. 밤에 스탑로스를 걸 수 없게 된다.
- **worker가 공표 + api가 관측으로 보강** — 채택.

## Decision

### 1. 커버리지 공표 — worker가 Postgres에 쓴다

```sql
CREATE TABLE realtime_price_coverage (
    stock_id     BIGINT      PRIMARY KEY REFERENCES stocks(id),
    source       VARCHAR(10) NOT NULL CHECK (source IN ('KIS','TOSS')),
    published_at TIMESTAMPTZ NOT NULL           -- 이 행을 마지막으로 공표한 시각(heartbeat)
);
```

- 시세 생산자 역할의 worker(`KisCoverageProvider`·`TossCoverageProvider`가 있는 프로세스)가 **기동 시와 60초마다** 자기 집합 전체를
  한 트랜잭션으로 공표한다: 집합의 행은 upsert(`published_at = now()`), 집합에 없는 행은 삭제. 실시세가 꺼져 있으면(`internal`)
  빈 집합 → 테이블이 비워진다.
- **선언된 집합이 아니라 연결이 살아 있는 집합을 공표한다.** KIS 웹소켓이 끊겼으면 KIS 종목은, Toss 연결(US/KR)이 끊겼으면 그 연결의
  종목은 빠진다. 웹소켓 단절은 한 시장이 통째로 조용해져 api 쪽 관측(§3-1)으로는 장 마감과 구별되지 않는다 — 연결 상태는 worker만
  안다(브랜치 리뷰에서 발견: 초안은 이 핵심 장애를 놓쳤다). 남는 빈틈: 연결은 살아 있는데 서버가 데이터를 안 보내는 경우.
- 공표자끼리 `pg_advisory_xact_lock`으로 직렬화하고 행을 `stock_id` 순으로 쓴다. k8s base는 role=all `worker`와 `worker-market`을
  함께 띄우므로 공표자가 여럿이다 — 같은 집합이면 무해하고, 설정이 달라 집합이 다르면 서로의 행을 지우며 깜박인다(설정을 맞춰야 한다).
- **공표가 끊기면 모른다**: api는 `published_at`이 **5분** 넘은 행을 커버리지로 인정하지 않는다(worker가 죽었거나 공표가 멈췄다).
- 여러 worker 레플리카가 같은 집합을 공표해도 결과는 같다(결정적 계산, 멱등 upsert).

### 2. 생성 시 — 커버리지 밖이면 거부

`ConditionalOrderService.create`/`createOco`는 계좌의 증권사가 실제 돈을 움직이면(§4) 대상 종목이 커버리지에 있는지 확인한다.
없으면 **409**: "이 종목은 실시간 시세가 연결돼 있지 않아 조건부 주문을 걸 수 없습니다." 발동할 수 없는 주문을 만들어 주지 않는다.

### 3. 상실 감시 — ACTIVE는 유지하고 드러낸다

이미 걸린 조건부 주문의 종목이 커버리지에서 빠지거나 장중 실시세가 끊겨도 **주문은 ACTIVE로 둔다**(돌아오면 다시 감시된다).
대신 숨기지 않는다:

- **조회 응답**에 `priceFeed: LIVE | STALE | NONE`을 싣는다.
  - `NONE` — 커버리지에 없다(공표 없음·만료).
  - `STALE` — 커버리지에는 있는데 **정규장 중** 이 종목의 실시세 틱이 **2분** 넘게 오지 않았다(api가 관측, §3-1).
  - `LIVE` — 그 밖. 장 마감 중에는 틱이 없는 게 정상이므로 커버리지에 있으면 `LIVE`다.
- 화면은 `NONE`/`STALE` 주문에 "실시세 없음 — 발동하지 않습니다" 배지를 단다.
- 메트릭 `conditional_order_active_without_feed{reason=none|stale}` 게이지 + **Ticket** 알림 `ConditionalOrdersWithoutPriceFeed`
  (0보다 크게 10분 지속). 사용자 푸시는 후속(engineering-backlog).

#### 3-1. 관측 — api가 받는 틱에서

`MarketTickBroadcastConsumer`(ADR-038: 모든 api 인스턴스가 모든 틱을 받는다)가 실시세 출처(ADR-055 `TickProvenance.source.isReal`)
틱마다 종목별 마지막 수신 시각을 인메모리 맵에 기록한다(`PriceFeedMonitor`, 다른 리스너보다 먼저 돈다). api에는 장 시간표가 없으므로
**이 종목의 마지막 실시세가 같은 시장의 가장 최근 실시세보다 5분 넘게 뒤처졌다**를 STALE로 본다. 장 마감처럼 시장 전체가 함께
조용해지면 아무도 뒤처지지 않으므로 STALE이 아니다. 실시세를 한 번도 관측하지 못한 시장과 api 기동 직후 5분은 판정하지 않는다
(LIVE — 모르는 것을 문제로 단정하지 않는다; 연결 단절은 §1의 공표가 NONE으로 잡는다).

초안은 "시장은 15분 안에 활발, 이 종목은 2분 넘게 조용"이었다 — 장 마감 직후 13분 동안 **모든** 커버 종목이 STALE이 돼 알림
(`for: 10m`)이 매일 울렸을 것이다(브랜치 리뷰에서 발견). 5분은 체결 틱이 뜸한 종목을 감안한 값이다.

### 4. ADR-055 게이트를 "실제 돈"에만 적용

`BrokerageClient`에 `movesRealMoney: Boolean`(기본 **true**)을 두고 `MockBrokerageClient`만 false로 선언한다. Mock 모드에서는 KIS·Toss
계좌도 Mock 클라이언트로 가므로, 판정 기준은 계좌의 `provider`가 아니라 **그 계좌에 연결되는 클라이언트**다.

- 평가기: 틱이 실시세가 아니어도, 그 조건부 주문 계좌의 클라이언트가 `movesRealMoney=false`면 평가한다(정규장·신선도 조건도 건너뛴다).
- 생성 시 커버리지 확인(§2)도 `movesRealMoney=true`일 때만.
- 평가기의 `@EventListener(condition = "#event.provenance.source.real")` 사전 필터(ADR-055)는 Mock 모드일 때 꺼야 한다 — 조건을
  `#event.provenance.source.real or @brokerageClientRegistry.anySimulated()`로 넓힌다. 실거래 모드에서는 지금과 같다. 레지스트리
  빈은 Mock/실거래 두 팩토리가 같은 이름(`brokerageClientRegistry`)으로 등록한다 — 동시에 하나만 존재하므로 SpEL이 늘 찾는다.
  Mock 모드에서는 합성 틱이 매초 모든 종목에서 오므로, **ACTIVE 조건부 주문이 걸린 종목만**(`ActiveConditionalStocks`, 5초 갱신)
  통과시킨다 — 그렇지 않으면 ADR-055가 막아 둔 @Async 큐 포화가 Mock 모드에서 되살아난다.

### 5. 커버리지 공급자는 키가 있어야 집합을 낸다

`KisCoverageProvider`·`TossCoverageProvider`는 `ingestion.source`뿐 아니라 플랫폼 키가 있어야 집합을 낸다. 키 없이 kis를 켜면
구독기는 아무것도 구독하지 않는데 집합은 선언돼, `MockPriceGenerator`가 그 종목들을 건너뛰어 **시세가 아예 멈추고**, 공표는 실시세가
없는 종목을 있다고 알렸을 것이다(구현 중 발견).

기본값이 true인 이유: 새 증권사 클라이언트가 이 속성을 잊으면 실제 돈으로 취급돼 게이트가 걸린다(fail-closed, ADR-055 §1과 같은 원칙).

## Reasons

- 커버리지의 단일 진실 소스(worker)를 복제하지 않고 공표한다. heartbeat가 끊기면 "모름"으로 안전하게 떨어진다.
- 공표(밤에도 답할 수 있다)와 관측(장중 끊김을 잡는다)이 서로의 빈 곳을 메운다.
- 발동할 수 없는 주문은 만들지 않고, 만들어진 뒤 그렇게 된 주문은 지우지 않되 드러낸다(사용자 결정, 2026-10-04).
- 개발 환경에서 조건부 주문 전 과정을 다시 시험할 수 있다. 실거래 경로의 보호는 줄지 않는다.

## Consequences

- 구현 중 worker 통합 테스트 베이스의 잠복 레이스가 드러났다 — 하위 클래스가 둘이 되자 `@Testcontainers`가 먼저 끝난 클래스에서
  공유 컨테이너를 꺼버렸다. api 베이스와 같은 방식(직접 `start()`, 정지 안 함)으로 고쳤다.

- **기본 배포(`internal`)에서는 실계좌 조건부 주문 생성이 전부 409가 된다.** 지금도 발동은 안 됐으니 사실을 드러내는 것이지만,
  실거래 공개 전에 `INGESTION_SOURCE`에 kis/toss를 켜야 한다(human-action-items: 플랫폼 키).
- **커버리지 집합의 선정 방식(ID 순 21종목)은 그대로다.** 사용자가 원하는 종목이 커버되지 않는 문제는 이 ADR이 고치지 않는다 —
  드러낼 뿐이다. 수요 기반 구독(사용자의 조건부 주문·관심 종목으로 구독 대상을 고른다)은 후속.
- worker가 60초마다 작은 쓰기를 한다(최대 ~120행 upsert). api는 조건부 주문 생성·조회마다 작은 조회를 한다.
- STALE 판정은 인스턴스마다 인메모리라 레플리카 간에 잠깐 다를 수 있다(모두 같은 틱을 받으므로 수 초 이내로 수렴).
- `movesRealMoney`는 Mock 모드 전체에 대한 스위치다. Mock 모드로 운영에 배포하면 실거래 보호가 꺼진 것처럼 보이지만, 그때는 실제
  주문도 나가지 않는다(Mock 증권사).

## Revisit When

- 수요 기반 구독을 도입할 때 — 공표 테이블이 "worker가 고른 집합"이 아니라 "구독 요청의 결과"가 된다. 생성 시 거부 대신 "구독을
  요청하고 성공하면 생성"이 가능해진다.
- KIS 다중 커넥션·앱키 풀링으로 커버리지가 동적으로 바뀌게 될 때 — 공표 주기를 이벤트 기반으로 바꾼다.
- 사용자 푸시 알림이 생기면 — `NONE/STALE`로 바뀌는 순간 알린다.
