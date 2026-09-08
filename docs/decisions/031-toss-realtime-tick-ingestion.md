# ADR-031: Toss 실시간 시세(trade:kr/us) 연동 설계

## Status
Accepted (설계만 — 구현은 의도적으로 다음 라운드로 미룸, 사용자 지시)

## Context

[ADR-030](030-kis-realtime-tick-ingestion.md)의 "Revisit When"이 명시한 다음 단계다: "Toss 플랫폼 앱키가 준비되면 — trade:kr/trade:us WebSocket 채널로 별도 라운드 진행." 이번엔 KIS 라운드와 같은 방식(공식 스펙 직접 확인 → ADR로 설계 → 구현)을 따르되, **사용자가 이번엔 설계까지만 요청**했다 — 코드는 이 ADR에 한 줄도 없다.

**Toss 실시간 채널 인증/한도를 AsyncAPI 스펙에서 직접 재확인했다**(`openapi.tossinvest.com/openapi-docs/latest/asyncapi.json`) — ADR-026이 이 스펙의 존재만 언급하고 세부는 검증하지 않았던 부분이다:

- **인증**: WebSocket 핸드셰이크 시 `Authorization: Bearer {access_token}` 헤더 1회만 필요하다 — "인증은 handshake 시점 1회이며, 연결 유지 중 액세스 토큰이 만료되어도 연결은 끊기지 않습니다." 토큰은 REST와 동일한 `POST /oauth2/token`(client_credentials)로 발급한다.
- **시장 데이터 채널은 계정 무관**: `trade:kr`/`trade:us`/`orderbook:kr`/`orderbook:us`는 "앱 단위" 인증이면 충분하다 — "푸시는 모든 세션에서 제공됩니다." (반대로 `personal:order:{accountSeq}`는 "본인 종합매매·활성 계좌만 구독" 가능한 계정 종속 채널이다.) 이 구분이 KIS와 동일한 결론을 준다 — 플랫폼 단일 앱키로 전체 시세를 커버할 수 있다.
- **한도가 명확한 숫자로 문서화돼 있다**(KIS의 41건과 달리 모호함이 없다): **연결당 구독 100건**("연결당 구독 수: 100건(`codes` 합산)", 채널×종목 조합 기준 — 예: `trade:us:AAPL`과 `orderbook:us:AAPL`은 종목이 같아도 채널이 다르므로 2건으로 카운트), **계정당 동시 연결 2개**("동시 연결: 계정당 2개 | 새 연결은 수락되고 가장 오래된 연결이 종료"), **구독 선언 빈도 초당 5회**(`rate-limit-exceeded` 에러 프레임), **180초 무수신 시 서버가 연결 종료**(60초 간격 keepalive 권장).
- **푸시 메시지 형식이 실제 예시로 확인됐다**: `{"type":"message","topic":"trade:us:AAPL","data":{"price":"243.26","volume":"8","timestamp":"2026-06-18T23:30:00.000+09:00","currency":"USD"}}` — **종목코드는 `data`가 아니라 `topic` 문자열에 `trade:{시장}:{symbol}` 형태로만 들어있다**(subscribe 시 보낸 `codes`가 그대로 echo됨). `data`엔 price/volume/timestamp/currency 4개 필드만 있다 — KIS의 46필드 pipe-delimited 프레임과 달리 JSON이라 파싱이 훨씬 단순하다.

**현재 종목 유니버스를 다시 정확히 확인했다**(이전 세션 요약의 "152 KR / 104 US" 수치는 산술이 맞지 않아 — 152+104=256≠202 — 신뢰하지 않고 DB로 재확인): `SELECT market, count(*) FROM stocks WHERE is_active=true GROUP BY market` → **KOSPI 102 + KOSDAQ 49 = 151 국내, NASDAQ 24 + NYSE 27 = 51 해외, 합계 202**. ADR-030은 국내 21종목만 커버했고 **미국 51종목은 완전히 미커버 상태로 남아있다** — 이번 설계가 메울 수 있는 정확한 공백이다.

**플랫폼 키가 KIS와 달리 아예 존재하지 않는다**: `backend/api`/`backend/worker` 어디에도 `toss.*` 앱 레벨 설정이 없다 — `app.brokerage.toss.base-url`만 있고 앱키/시크릿은 전부 `TossBrokerageClient.issueToken(appKey, appSecret)`처럼 **호출 시점에 사용자별 BYOK 값을 그대로 전달**받는 구조다(`TossBrokerageClient.kt` 상단 주석: "TOSS_APP_KEY — client_id (사용자별 BYOK, .env에 넣지 않음)"). KIS는 최소한 `kis.app-key`/`kis.app-secret` 자리가 미리 있었지만, Toss는 이번에 처음부터 새 설정 축을 만들어야 한다.

## Decision

1. **`backend/worker/.../toss/` 패키지를 `.../kis/`와 같은 모양으로 신설한다**(실제 구현은 다음 라운드): `TossWebSocketClient`(연결·인증·구독·재연결), `TossRealtimeHandler` 인터페이스(`val channelPrefix: String`, `fun handle(topic: String, data: JsonNode)` — KIS의 pipe-delimited `List<String>` 대신 JSON이라 시그니처가 다르다), `TossExecutionTickHandler`(trade 채널 → `market.ticks`), `TossCoverageProvider`(KIS의 `KisCoverageProvider`와 동일한 역할), `TossExecutionTickSubscriber`. `backend/api`의 `TossBrokerageClient.issueToken()`과 로직은 같지만 **재사용할 수 없다** — api/worker는 별도 JVM이라 클래스를 공유할 수 없다(ADR-029에서 `PriceBroadcaster`/`TickKafkaConsumer` 분리 때와 같은 제약). worker 안에 최소한의 자체 토큰 발급 함수를 하나 더 둔다.

2. **신규 플랫폼 설정 `toss.platform.app-key`/`toss.platform.app-secret`(env: `TOSS_PLATFORM_APP_KEY`/`TOSS_PLATFORM_APP_SECRET`)을 쓴다 — `TOSS_APP_KEY`/`TOSS_APP_SECRET`이라는 이름은 절대 재사용하지 않는다.** 그 이름은 이미 `TossBrokerageClient.kt` 주석이 "사용자별 BYOK, .env에 넣지 않음"이라고 명시적으로 경고한 이름이다 — 같은 이름을 플랫폼 키로 `.env`에 넣으면, 나중에 누군가 그 주석만 보고 "이건 .env에 넣으면 안 되는 값"으로 오인해 실수로 지우거나, 반대로 실제 BYOK 코드 경로가 실수로 그 전역 환경변수를 읽어버리는 두 방향의 사고를 다 열어둔다.

3. **KIS와 겹치지 않는 상보적 커버리지로 배분한다**(중복이 아니라 공백을 메운다):
   - **연결 A**: `trade:us` — 미국 51종목 전체(ADR-030이 전혀 다루지 않은 영역).
   - **연결 B**: `trade:kr` — `KisCoverageProvider.coveredStockIds`(21종목)를 제외한 국내 종목 중 최대 100개.
   - 계정당 동시 연결 2개 한도를 정확히 다 쓴다. 국내 최대 커버리지: 21(KIS) + 100(Toss) = 121/151. 미국: 51/51(Toss 단독으로 완전 커버). 전체: 최대 172/202 — Mock에 남는 건 국내 30종목뿐이다.

4. **`ingestion.source`를 콤마 구분 다중값으로 일반화한다.** 지금은 `internal|kafka|kis` 중 정확히 하나였는데(ADR-030), KIS와 Toss를 동시에 켜려면 `ingestion.source=kis,toss`처럼 둘 다 표현할 수 있어야 한다. `MarketTickScheduler`/`KisExecutionTickSubscriber`/신규 `TossExecutionTickSubscriber`의 조건식을 정확히-일치(`havingValue`/`matches`)에서 `contains(token)` 방식으로 바꾼다. **`ingestion.source=kis` 단독 사용은 동작이 전혀 바뀌지 않는다** — ADR-030을 뒤집는 게 아니라 그 스위치를 다중값으로 확장하는 것뿐이다.

5. **`MockPriceGenerator`가 `KisCoverageProvider`와 신규 `TossCoverageProvider` 양쪽의 커버 종목 합집합을 건너뛴다** — 두 프로바이더가 동시에 켜져 있어도 정확히 실제 커버리지만큼만 Mock을 대체한다.

6. **60초 간격 keepalive ping을 별도로 구현한다.** KIS와 달리 Toss는 180초 무수신 시 서버가 먼저 끊는다고 명시돼 있다 — `@Scheduled(fixedDelay=60_000)`로 빈 프레임 또는 스펙이 안내하는 핑을 전송한다. 토큰은 핸드셰이크 1회만 필요하므로 KIS처럼 만료 시각을 추적해 재발급할 필요는 없다(연결이 유지되는 한).

7. **구독 선언은 종목 리스트를 배열로 한 번에 묶어 보낸다**(`{"type":"trade:kr","codes":["005930","000660",...]}`) — 종목 하나당 메시지 하나씩 보내면 "초당 5회" 한도에 걸릴 수 있지만, 연결당 최대 100종목을 배열 하나로 선언하면 통상 1~2개의 declare 메시지로 끝난다.

## Reasons

- **앱 레벨 인증이 성립하는 이유**: AsyncAPI 스펙이 시장 데이터 채널을 계정 종속이 아니라고 명시했으므로("모든 세션에 제공"), KIS와 마찬가지로 운영자 개인 명의의 Toss Open API 앱 하나가 플랫폼 전체 시세를 공급할 수 있다 — 확인 없이 가정한 게 아니라 스펙 문구로 확정했다.
- **새 설정 이름을 쓰는 이유**: 기존 `TOSS_APP_KEY`/`TOSS_APP_SECRET`는 코드 주석이 이미 "BYOK 전용, .env 금지"라고 선언한 이름이다. 같은 이름을 다른 목적으로 재사용하면 이름만 보고 판단하는 사람(운영자, 이후 세션의 나 자신 포함)을 두 방향으로 오도할 수 있다 — 이번 세션에서 이미 여러 번 "이름/타입이 맞는 줄 알았는데 실은 달랐다"는 버그를 겪었기 때문에(KIS 필드 대소문자, `.error`/`.message`) 이런 충돌 소지를 설계 단계에서 미리 없앤다.
- **미국 51종목을 우선 채우는 이유**: ADR-030은 국내만 다뤘고 미국은 전혀 손대지 않았다 — 같은 종목을 두 프로바이더가 중복으로 커버하는 것보다, 완전히 비어있는 영역을 먼저 메우는 게 실질적 커버리지 증가 폭이 훨씬 크다(0/51 → 51/51 vs 21/151 → 42/151).
- **`ingestion.source`를 새 설정 키로 바꾸지 않고 콤마 구분으로 확장한 이유**: 운영자가 이미 알고 있는 단일 프로퍼티 이름을 유지하면서, 기존 `kis` 단일값 사용을 전혀 깨지 않는 최소 변경이다. 완전히 새로운 boolean 플래그 세트(`ingestion.kis.enabled`, `ingestion.toss.enabled`)를 만드는 대안도 고려했지만, 지금 딱 2~3개 프로바이더 규모에서는 과설계로 판단했다.
- **worker 내부에 토큰 발급을 중복 구현하는 이유**: `backend/api`의 `TossBrokerageClient`와 로직은 사실상 같지만 별도 JVM이라 클래스 자체를 공유할 수 없다 — ADR-029에서 `PriceBroadcaster`를 worker에서 직접 호출할 수 없어 Kafka 컨슈머를 새로 둔 것과 동일한 제약이다. 코드 중복은 있지만, 두 서비스를 억지로 묶는 것보다 낫다.

## Consequences

- **이 ADR 시점엔 코드가 전혀 없다** — `backend/worker/.../toss/` 디렉터리 자체가 아직 없다. 다음 라운드가 실제로 클래스를 만들고 컴파일·테스트·(플랫폼 키가 생기면) 라이브 검증까지 진행해야 한다.
- 플랫폼 시크릿이 KIS 것(`KIS_APP_KEY`/`KIS_APP_SECRET`)에 이어 2개 더 늘어난다(`TOSS_PLATFORM_APP_KEY`/`TOSS_PLATFORM_APP_SECRET`) — 운영 시 시크릿 관리 항목이 늘어난다.
- KIS(60초 주기 재연결 스케줄러 방식)와 Toss(60초 keepalive ping + 180초 서버 타임아웃 방식)가 서로 다른 커넥션 유지 전략을 갖게 된다 — 통합된 "실시간 커넥션 관리자" 추상화는 만들지 않았다(아직 프로바이더가 2개뿐이라 과설계로 판단, Reasons 참고). 3번째 프로바이더가 생기면 재검토할 만하다.
- `trade:kr`/`trade:us`가 커버하지 않는 `orderbook:kr`/`orderbook:us`, `personal:order`(계정별 주문 체결 푸시 — REST 폴링 대신 쓸 수 있는 잠재적 개선점)는 이번 설계 범위 밖이다.
- 국내 종목 배분(KIS 21 + Toss 최대 100)은 DB id 순 정적 선택이다 — ADR-030과 같은 한계(실제 유동성/사용자 관심과 무관)를 그대로 물려받는다.

## Revisit When

- 이 설계를 실제로 구현할 때 — ADR-030처럼 코드 작성 → 컴파일/테스트 → (플랫폼 키가 있다면) 라이브 검증까지 진행.
- 플랫폼 Toss 앱키(`TOSS_PLATFORM_APP_KEY`/`SECRET`)가 실제로 발급되면 — 라이브 검증이 비로소 가능해진다.
- `personal:order` 채널을 `backend/api`의 주문 상태 폴링(`getOrderStatus` REST 호출) 대신 쓰는 걸 검토할 때 — 이번 ADR은 시세(market data)만 다루고 브로커리지 주문 흐름은 건드리지 않는다.
- 국내 나머지 30종목까지 실시간 커버리지가 필요해지거나, 3번째 실시간 프로바이더가 추가돼 커넥션 관리 로직 중복이 부담될 때.
