# ADR-030: KIS 실시간 체결가(H0STCNT0) 실데이터 연동

## Status
Accepted

## Context

[ADR-029](029-price-broadcast-pipeline.md)로 STOMP 푸시 파이프라인 자체는 복구했지만, `market.ticks`를 채우는 건 여전히 `MockPriceGenerator`(내부 모드) 또는 Go `market-gateway`(kafka 모드)의 합성 데이터뿐이다. [ADR-023](023-commercialization-pivot.md)이 이미 이 작업을 로드맵 항목으로 명시했다: "Toss/KIS 실시세 producer로 교체한다."

**플랫폼 키 vs BYOK 키는 완전히 다른 것이다.** `backend/worker`의 `kis.app-key`/`kis.app-secret`(`KIS_APP_KEY`/`KIS_APP_SECRET` 환경변수)은 플랫폼 자체가 KIS Developers에 등록한 단일 앱 키로, 202개 전체 종목의 시세를 모든 사용자에게(브로커리지 미연동 사용자 포함) 공급하기 위한 것이다. 이번 세션에서 지금까지 실검증한 건 전부 사용자 개인 BYOK 브로커리지 키(`BrokerageAccount`, ADR-025~028)였고, 플랫폼 키는 **로컬에 값이 전혀 설정된 적이 없다** — 확인 결과 `backend/worker/.env`, `backend/api/.env`, `.env` 어디에도 파일 자체가 없다. 즉 이번 라운드 이전까지 `KisWebSocketClient`(호가 구독용으로만 존재)는 코드로는 존재하되 **단 한 번도 실제로 기동된 적이 없는 죽은 경로**였다.

**H0STCNT0 필드 순서를 KIS 공식 저장소에서 직접 확인했다** (`examples_llm/domestic_stock/ccnl_krx/ccnl_krx.py`, `koreainvestment/open-trading-api`) — 저장소 어디에도 이 필드 순서에 대한 기존 문서/코드가 없었다(호가용 H0STASP0만 `KisOrderBookHandler.kt` 주석에 문서화돼 있었다). 46개 필드 중 이번에 쓰는 건: idx0 `MKSC_SHRN_ISCD`(종목코드), idx1 `STCK_CNTG_HOUR`(체결시각 HHMMSS), idx2 `STCK_PRPR`(현재가), idx12 `CNTG_VOL`(당일 이 체결의 거래량).

**등록 건수 제한도 이번에 새로 확인했다**: KIS 공식 저장소의 Code Assistant MCP 프롬프트(`MCP/KIS Code Assistant MCP/src/prompts/prompt.py`)가 "1개 appkey당 최대 41건 등록 제한"을 명시한다. 기존 `KisOrderBookSubscriber.kt`의 `LIMIT 100`은 이 실제 한도를 검증 없이 초과하는 값이었다 — 다만 이 경로 자체가 지금까지 한 번도 실행된 적이 없어 아무도 이 초과를 겪지 못했을 뿐이다. **41건이 "커넥션당"인지 "appkey당(여러 커넥션에 걸쳐 전역)"인지는 공식 문서만으로 확정할 수 없었다** — KIS 예제 코드는 언제나 커넥션 1개로만 시연하기 때문에 구분이 드러나지 않는다. 이 모호함을 실 서버 테스트 없이 추측으로 풀지 않기로 했다(이번 세션에서 이미 두 번 — KIS 필드 대소문자, worker Kafka 컨슈머 설정 — "확인 없이 가정"이 실버그로 이어진 전례가 있다).

**Toss 실시간 시세는 실재를 재확인했다.** ADR-026이 언급한 `wss://openapi-ws.tossinvest.com/ws/v1`과 AsyncAPI 스펙(`.../asyncapi.json`)을 이번에 직접 다시 확인했고, `trade:kr`/`trade:us`, `orderbook:kr`/`orderbook:us` 채널이 실제로 정의돼 있음을 확인했다(JSON 기반이라 KIS의 pipe-delimited 파싱보다 훨씬 단순한 구조). 다만 Toss도 마찬가지로 플랫폼 앱키가 필요하고 현재 로컬에 설정된 값이 없으며, Toss는 모의투자 서버가 없어 모든 연결이 실서버다.

**사용자 결정**(AskUserQuestion): (1) 코드는 지금 구현하되, 플랫폼 KIS 앱키가 실제로 준비되기 전까지 실 서버 라이브 검증은 보류한다. (2) 이번 라운드는 KIS만 다루고 Toss는 다음으로 미룬다.

## Decision

1. **Go `market-gateway`가 아니라 기존 `backend/worker`의 Kotlin KIS 인프라를 확장한다.** [ADR-005](005-kafka-go-gateway-netty-broadcast.md)의 "Revisit When"은 "KIS 실시세를 Go 게이트웨이로 옮기는 게 자연스러운 다음 단계"라고 예상했지만, 실제로 보니 승인키 발급/캐싱/WebSocket 연결/pipe-delimited 파싱 인프라가 이미 Kotlin에 동작 가능한 형태로 존재한다(`KisWebSocketClient`, 호가용으로 검증됨). 이걸 Go로 처음부터 다시 구현하는 건 같은 로직을 다른 언어로 중복 구현하는 리스크만 지고 얻는 게 없다 — ADR-005 스스로도 "포트폴리오/학습 목적이지 부하 대응이 아니다"라고 명시한 결정이라, 그 예상을 그대로 따를 강한 이유가 없다. `PriceBroadcaster`(ADR-029)가 API 쪽에만 있었던 것과 반대로, 이번엔 인프라가 worker 쪽에 이미 있다는 게 핵심 차이다.

2. **`KisWebSocketClient`를 TR 타입에 무관하게 다중 핸들러를 디스패치하도록 일반화한다.** `KisRealtimeHandler` 인터페이스(`val trId`, `fun handle(parts)`)를 도입해 `KisOrderBookHandler`(H0STASP0)와 신규 `KisExecutionTickHandler`(H0STCNT0)가 같은 커넥션을 공유한다. `subscribe(symbol)` → `subscribe(trId, symbol)`로 시그니처 변경.

3. **41건 한도를 코드 레벨에서 방어적으로 강제한다** — `KisWebSocketClient`가 등록 시도 시 현재 (trId,symbol) 등록 수가 41을 넘으면 전송을 거부하고 경고 로그를 남긴다(서버가 조용히 무시하게 두지 않는다). 한도의 "커넥션당 vs appkey당 전역" 모호성이 실 서버 검증 전까지 해소되지 않았으므로, **다중 커넥션 풀링은 이번 라운드에서 만들지 않는다** — 확인 안 된 가정 위에 풀링을 지었다가 appkey 자체가 rate-limit/차단되는 리스크를 지지 않기 위함이다. 대신 하나의 커넥션에 호가(20건)+체결가(21건) = 41건으로 정적 분할한다.

4. **`ingestion.source=kis` 신규 모드 추가.** `MarketTickScheduler`의 조건식을 `!= 'kafka'`(기존 내부/카프카 이분법)에서 `matches('internal|kis')`로 바꿔 kis 모드에서도 스케줄러 자체는 계속 돈다 — 단 `MockPriceGenerator`가 `KisCoverageProvider`(신규, KIS가 실제로 구독한 종목 ID 집합을 단일 진실 소스로 계산)를 참조해 그 종목들만 생성을 건너뛴다. 결과: KIS가 커버하는 국내 21종목은 실시세, 나머지 131개 국내 종목과 미국 104종목은 계속 Mock — 종목별로 정확히 실제 커버리지만큼만 Mock을 대체한다.

5. **미국 종목·Toss는 이번 범위에서 제외.** KIS의 해외 실시간 체결(별도 TR, 이번에 조사 안 함)이나 Toss WS 둘 다 후속 라운드로 미룬다.

## Reasons

- **Kotlin 확장 vs Go 재구현**: 이유 1 참고 — 이미 동작 검증된(호가 경로로) 인증/연결/파싱 인프라를 재사용하는 게 처음부터 새 언어로 스펙을 다시 구현하는 것보다 리스크가 명백히 낮다.
- **다중 커넥션 풀링을 짓지 않은 이유**: 41건 한도의 정확한 범위(커넥션당 vs appkey당)를 문서만으로 확정할 수 없는 상태에서 "커넥션을 늘리면 늘어난 만큼 더 구독된다"는 가정에 기대 풀을 만드는 건, 그 가정이 틀렸을 경우(appkey당 전역이라면) 아무 이득 없이 복잡도만 늘고 최악의 경우 KIS 서버가 이상 트래픽으로 appkey를 제재할 수 있다. 정적 41건 분할은 가정 없이 안전하게 동작한다.
- **20/21 분할**: 호가 구독은 기존에 이미 (죽어있던 채로) 존재하던 기능이고, 이번 라운드의 실제 목표는 체결가다. 완전히 한쪽에 41을 몰아주지 않고 두 기능이 공존 가능하게 균등에 가깝게 나눴다 — 정확한 비율보다 "합계가 확인된 한도를 넘지 않는다"는 안전성이 중요했다.
- **종목 단위 Mock 대체(전체 KOSPI/KOSDAQ 제외가 아니라)**: `KisCoverageProvider`가 실제로 구독에 성공한(혹은 성공을 시도하는) 정확히 그 종목 ID만 계산해 Mock 쪽에 공유하므로, 21종목을 넘는 나머지 국내 종목이 "KIS 모드니까 당연히 실시세겠지"라는 잘못된 기대 속에 조용히 시세가 멈추는 걸 막는다.
- **`dataCount` 다중 체결 프레임의 첫 건만 처리**: KIS는 짧은 시간에 여러 체결이 몰리면 한 메시지에 46필드 블록을 여러 번 이어붙여 보낼 수 있다(`dataCount` 필드로 개수 표시). 기존 H0STASP0 파서도 이 다중화를 처리하지 않는 전례를 따라, 이번에도 첫 블록만 파싱한다 — 고빈도 구간에서 일부 체결이 유실될 수 있다는 걸 알고 넘어가는 것이지, 몰라서 놓친 게 아니다. 완전한 프레임 다중화 처리는 후속 과제.

## Consequences

- `backend/worker`가 이제 진짜 실시간 시세 소스를 하나 갖는다 — 단, 국내 21종목뿐이고 플랫폼 KIS 앱키가 실제로 설정돼야만 동작한다(설정 전엔 `isConfigured=false`로 완전히 비활성, 기존 동작과 동일하게 안전).
- 라이브 검증은 보류 상태다 — 플랫폼 KIS 앱키가 실제로 발급/설정되기 전까지 이 경로는 컴파일과 단위 테스트로만 검증됐다. H0STCNT0 필드 순서는 KIS 공식 저장소에서 직접 확인했지만, 실 서버 프레임으로 검증한 적은 없다 — 다음에 이 코드를 다시 만질 땐 "이미 검증됨"으로 가정하지 말 것.
- 41건 한도를 20/21로 정적 분할했기 때문에, 두 기능을 합쳐도 202종목 중 41종목(호가 20 + 체결가 21, 겹치는 종목이면 41 미만)만 KIS 실시간 커버리지를 갖는다. 완전한 커버리지는 다중 커넥션/앱키 풀링이 필요하며, 41건 한도의 정확한 범위를 실 서버로 먼저 확인한 뒤에나 설계할 수 있다.
- 기존 `KisOrderBookSubscriber`의 `LIMIT 100`이 실제 한도(41)를 넘는 값이었다는 걸 이번에 발견했다 — 이번 ADR로 20으로 낮춰 즉시 위험을 줄였지만, 그 기능 자체(호가)의 재설계는 이번 범위 밖이라 별도 후속 작업으로 분리했다.
- Toss·미국 종목은 여전히 100% Mock이다.

## Revisit When

- 플랫폼 KIS 앱키가 실제로 발급되면 — 가장 먼저 41건 한도가 커넥션당인지 appkey당 전역인지 실 서버로 확인한다. 이 결과에 따라 다중 커넥션 풀링이 유효한 확장 경로인지, 아니면 여러 개의 별도 앱키가 필요한지가 갈린다.
- 202종목 전체(또는 국내 152종목 전체) 실시간 커버리지가 필요해질 때 — 위 확인 이후 커넥션/앱키 풀링을 설계한다.
- Toss 플랫폼 앱키가 준비되면 — `trade:kr`/`trade:us` WebSocket 채널(AsyncAPI로 확인됨, JSON 기반이라 KIS보다 파싱이 단순하다)로 별도 라운드 진행.
- 미국 종목 실시간 체결이 필요할 때 — KIS 해외 실시간 체결 TR(이번에 조사 안 함) 또는 Toss `trade:us` 중 선택.
- `KisOrderBookSubscriber`의 재설계(다중화·한도 재검증) 시.
