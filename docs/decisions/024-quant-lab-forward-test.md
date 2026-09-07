# ADR-024: Quant Lab 포워드 테스트 설계

## Status
Accepted

## Context

[docs/product.md](../product.md)의 상용화 로드맵과 [ADR-023](023-commercialization-pivot.md)은 "Quant Lab 백테스트 엔진·포워드 테스트·룰셋 JSON 구조는 이미 설계·구현되어 있다"고 기술하고, [docs/architecture.md](../architecture.md)도 Forward Test Engine을 "Done"으로 표기한다. 실제 코드베이스를 확인한 결과 이는 사실이 아니다 — `ForwardTest`로 시작하는 클래스, `/forward-test/*` 엔드포인트가 전혀 없고, 포워드 테스트용으로 만들어진 `quant_signals` 테이블(V13 마이그레이션, `mode VARCHAR(20) DEFAULT 'FORWARD_TEST'` 컬럼 포함)도 참조하는 코드가 없다. 이 ADR은 완전히 새로 설계하는 것이며, 위 문서들의 "완료" 표기는 이 ADR과 후속 구현이 끝나는 대로 정정한다.

포워드 테스트는 이미 백테스트를 통과한 룰셋을 실시간(가상)으로 계속 평가해, 실제 돈을 걸기 전에 "지금 이 순간에도 이 전략이 신호를 내는가"를 검증하는 기능이다. 룰 평가 로직(`IndicatorEngine`, `RuleEvaluator`) 자체는 이미 순수 함수로 구현되어 있어 재사용 가능하지만, 아래 세 가지는 새로 설계해야 한다:

1. **트리거 방식** — 캔들 파이프라인은 이벤트 기반(`CandleAggregator`가 매 분 `candles_1d`를 upsert)이지만, [ADR-021](021-candles-1d-realtime-upsert.md)이 명시하듯 장중 "오늘" 로우는 계속 바뀌는 미확정값이다. "확정 종가"라는 개념 자체가 스키마에 없다.
2. **포지션 상태 저장** — 백테스트는 메모리 루프 안에서 `entryPrice`/`holding`을 추적하고 실행이 끝나면 버린다. 포워드 테스트는 매일 실행되고 상태가 이어져야 하므로 영속 저장소가 필요하다.
3. **신호 전달** — 사용자가 신호 발생을 실시간으로 알 수 있어야 한다.

## Decision

### 1. 트리거: 장 마감 후 일 1회 cron

`BatchJobScheduler`(regime classification 등)와 동일한 `@Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")` 패턴을 그대로 따른다. KST 16:00은 KRX 정규장(15:30 마감) 이후로, 그 시점 이후엔 더 이상 틱이 들어오지 않으므로 `candles_1d`의 "오늘" 로우가 사실상 확정값이 된다 — ADR-021이 제안한 별도의 "확정 종가" 컬럼/테이블을 새로 만들지 않고도 같은 효과를 얻는다.

캔들 upsert 이벤트마다 반응하는 방식(`AlertEvaluator` 패턴)은 채택하지 않는다. 룰 DSL이 일봉 단위(period=20일 이동평균 등)이고 백테스트도 "하루 1번 평가" 단위이므로, 포워드 테스트도 같은 단위를 써야 두 결과를 나란히 비교할 수 있다. 장중 여러 번 재평가하면 신호가 뒤집힐 수 있어(ADR-021의 경고) 이 비교 가능성이 깨진다.

### 2. 포지션 상태: 새 테이블 2개 + 기존 `quant_signals` 확장

```sql
CREATE TABLE quant_forward_tests (
    id                   BIGSERIAL PRIMARY KEY,
    rule_set_id          VARCHAR(24) NOT NULL,
    rule_set_version     INT NOT NULL,
    stock_id             BIGINT NOT NULL REFERENCES stocks(id),
    status               VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    initial_capital      NUMERIC(18,2) NOT NULL,
    cash                 NUMERIC(18,2) NOT NULL,
    holding_qty          INT NOT NULL DEFAULT 0,
    holding_entry_price  NUMERIC(18,4),
    holding_entry_date   DATE,
    last_evaluated_date  DATE,
    started_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    stopped_at           TIMESTAMPTZ
);
-- 룰셋당 동시에 하나의 RUNNING 인스턴스만 허용
CREATE UNIQUE INDEX idx_quant_forward_tests_active ON quant_forward_tests(rule_set_id) WHERE status = 'RUNNING';

CREATE TABLE quant_forward_test_equity (
    id                BIGSERIAL PRIMARY KEY,
    forward_test_id   BIGINT NOT NULL REFERENCES quant_forward_tests(id) ON DELETE CASCADE,
    eval_date         DATE NOT NULL,
    equity            NUMERIC(18,2) NOT NULL,
    drawdown          NUMERIC(9,6) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (forward_test_id, eval_date)
);

ALTER TABLE quant_signals ADD COLUMN forward_test_id BIGINT REFERENCES quant_forward_tests(id);
ALTER TABLE quant_signals ADD COLUMN eval_date DATE;
CREATE UNIQUE INDEX idx_quant_signals_dedup ON quant_signals(forward_test_id, eval_date) WHERE forward_test_id IS NOT NULL;
```

`quant_forward_tests`가 "지금 어떤 상태인가"(포지션, 현금)를 들고, `quant_forward_test_equity`가 백테스트의 `equityCurve`와 동일한 모양으로 하루치씩 쌓인다. `quant_signals`는 원래 계획대로 BUY/SELL 로그로 쓰되, `forward_test_id`+`eval_date` 유니크 인덱스로 "같은 실행 회차의 같은 날짜엔 신호 최대 1건" 을 DB 레벨에서 강제한다 — 스케줄러가 중복 실행되거나(다중 인스턴스 배포 시) 수동 재시도가 있어도 안전하다. 이 덕분에 워커 쪽 `@DistributedLock` 패턴을 가져오지 않아도 된다(현재 `BatchJobScheduler`류의 API 쪽 스케줄 작업도 분산 락을 쓰지 않는 것과 일관됨).

**범위 제한**: 룰셋 하나당 단일 종목만 포워드 테스트한다(백테스트와 동일 — `universeJson`/다종목 유니버스는 이 ADR의 범위 밖). 룰셋을 재시작하면 새 `quant_forward_tests` 행이 생기고 이전 실행 이력은 STOPPED 상태로 남는다.

### 3. 신호 전달: 기존 STOMP 인프라 재사용, 리소스 스코프 토픽

`docs/architecture.md`가 언급한 `/topic/signals/{userId}` 대신 `/topic/rulesets/{ruleSetId}/signals`를 쓴다. 기존 `/topic/stocks/{id}` 관례(리소스 ID 기준 스코프)와 일치하고, 프론트엔드가 JWT를 디코드해 userId를 뽑아낼 필요 없이 이미 보고 있는 룰셋 ID로 바로 구독할 수 있다. `PriceBroadcaster`와 동일하게 `SimpMessagingTemplate.convertAndSend`로 `{type, direction, stockId, price, evalDate}` 맵을 보낸다.

## Reasons

- 백테스트가 이미 확립한 "하루 1번, 일봉 기준 평가"라는 단위를 그대로 따르면 `IndicatorEngine`/`RuleEvaluator`를 코드 변경 없이 재사용할 수 있고, 포워드 테스트 결과와 백테스트 결과를 같은 기준으로 비교할 수 있다.
- 장 마감 후 cron은 기존 `BatchJobScheduler` 패턴을 그대로 따르는 것이라 새 인프라(Spring Batch Job/Step, 분산 락, 별도 워커 역할)를 도입하지 않고도 구현 가능하다.
- `quant_signals`에 유니크 인덱스를 추가하는 것만으로 멱등성을 확보해, 별도의 분산 락 없이도 다중 인스턴스 환경에서 안전하다.

## Consequences

- "확정 종가" 개념을 정식으로 스키마에 넣지 않고 "16시 이후엔 안 바뀐다"는 운영 가정에 의존한다 — 향후 실거래소 데이터 연동 시 애프터마켓/거래정지 등으로 이 가정이 깨질 수 있다.
- 룰셋당 단일 종목만 지원 — 여러 종목에 동시에 포워드 테스트를 걸고 싶으면 룰셋을 복제해야 한다(백테스트와 동일한 제약이라 일관성은 있음).
- `quant_forward_tests`/`quant_forward_test_equity`는 새 테이블이라 마이그레이션이 필요하고, `quant_signals`는 스키마 변경(컬럼 추가)이 필요하다.
- 포워드 테스트 실행 중에는 룰셋을 수정할 수 없도록 막아야 한다(그렇지 않으면 진행 중인 실행의 룰 정의가 도중에 바뀌는 문제 발생) — `RuleSetService.update()`에 가드 추가 필요.

## Revisit When

- 실제 KIS/Toss 실시세 연동 이후, 애프터마켓 거래나 거래정지 종목에서 "16시 이후 미확정" 가정이 깨지는 사례가 발견되면 ADR-021이 언급한 별도의 "확정 종가" 개념을 정식 도입해야 한다.
- 여러 종목에 대한 유니버스 스크리닝 기반 포워드 테스트 요구가 생기면 `quant_forward_tests`를 룰셋당 1행이 아니라 (룰셋, 종목) 쌍당 여러 행으로 확장하는 재설계가 필요하다.
