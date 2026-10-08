# ADR-086: KRX 거래일 캘린더는 DB 테이블 하나가 출처이고, 모르는 해는 주말 규칙으로 계산하되 드러낸다

## Status
Accepted

## Context

영업일 계산이 곳곳에서 "토·일만 건너뜀"이었다.

- api `BusinessDayCalculator`(모의 정산 T+2), `BrokerageService.createSettlementFromFill`(실거래 정산 T+2), `MockBrokerageClient` — 각자 사본.
  추석 전날(2026-09-23) 체결은 T+2가 9/25(추석)로 잡혔고, 16:30 정산 배치(`MON-FRI` cron)는 공휴일을 몰라 **휴장일에 정산**했다.
  기준일도 `LocalDate.now()`(서버 기본 시간대)라 UTC 서버에서는 KST 00:00–09:00 체결이 하루 앞당겨졌다.
- worker `MarketSchedule`(모의 틱 생성·실시세 틱의 세션 상태), 지수 수집기(`market_index_daily` 일봉), Mock 지수 이력 — 공휴일에도 장이 열린 것으로 봤다.
- 웹 상단 장 상태는 클라이언트에서 평일 09:00–15:30으로 계산했고, 정산 화면·리플레이 날짜 띠도 주말만 뺐다.

고려한 대안:

1. **라이브러리/규칙 계산(음력 변환 + 대체공휴일 규칙)** — 임시공휴일·선거일·KRX 자체 휴장(연말)을 규칙으로 만들 수 없다. 법이 바뀌면(2026 제헌절 재지정,
   노동절 공휴일화) 코드 배포가 필요하다.
2. **외부 API 실시간 조회(KRX·공공데이터포털 특일 API)** — 장애가 곧 정산 장애가 된다. 공공 특일 API는 KRX 휴장(연말·근로자의 날)을 다 담지 않는다.
3. **DB 테이블 + 메모리 스냅샷** — 채택. 운영자가 확인한 날짜를 마이그레이션으로 넣고, api·worker가 같은 테이블을 읽는다.

갱신 방법도 두 가지를 놓고 골랐다.

- **(a) Flyway 후속 마이그레이션** — 채택.
- (b) 관리자 전용 쓰기 API — 실거래 정산일을 바꾸는 쓰기 경로를 인터넷에 노출하게 된다(관리자 권한 모델이 아직 얇다). 1년에 한두 번 바뀌는 데이터에
  쓰기 표면을 새로 만들 이유가 없다. 마이그레이션은 리뷰·이력·환경 간 동일성이 그냥 생긴다.

## Decision

### 데이터 (V83)

```
market_holidays(market, holiday_date, name, source)   PK(market, holiday_date), 평일만(CHECK ISODOW 1~5)
market_calendar_years(market, year, verified, note)    "이 해의 휴장일을 다 넣었다"는 표시
```

- 평일 휴장일만 넣는다. 주말은 규칙으로 휴장이다.
- **커버리지를 휴장일 행과 분리한다.** 행이 하나라도 있으면 그 해를 안다고 보면, 내년 임시공휴일 한 건만 미리 넣어도 나머지 공휴일이 조용히 영업일이 된다.
- 시드는 2026·2027(규칙으로 유도, `source=SEED_RULE_DERIVED`, `verified=false`). 2026은 언론 보도의 KRX 휴장일 17일과 대조했고,
  2027은 KRX 공고 전이다. 아래 "운영" 절차로 공식 공고와 대조해야 한다.

### 코드

- api `common.calendar`(OPEN 공유 커널, ADR-019): `TradingCalendar` 인터페이스, 불변 스냅샷 `KrxCalendar`, DB 스냅샷을 1시간마다 갈아 끼우는
  `MarketCalendar` 빈, 세션 규칙 `KrxSession`, 정산일 재정렬 규칙 `SettlementDateRealignment`. 모든 모듈이 `common`에 의존할 수 있어 Modulith 경계를 늘리지 않는다.
- worker `marketdata.KrxHolidayCalendar` + `KrxHolidayCalendarLoader`가 같은 테이블을 읽어 `MarketSchedule.krCalendar`(프로세스 전역 스냅샷)에 꽂는다.
  api·worker는 별도 Gradle 프로젝트라 코드를 공유하지 못한다 — **데이터 출처는 하나, 판단 규칙(주말 + 휴장일 + 미커버 해 대체)은 두 곳에 같은 모양**으로 둔다.
- 날짜 계산은 모두 `Asia/Seoul` 명시. 체결일 = 체결 시각의 KST 날짜, 휴장일·주말 체결(모의는 장외 체결이 있다)은 다음 영업일 체결로 본다.
  `settlementDate(tradedAt) = addBusinessDays(nextBusinessDay(kstDate(tradedAt)), 2)`.

### 모르는 해

캘린더에 없는 해를 물으면 **주말만 휴장으로 계산하되** 조용히 넘어가지 않는다.

- WARN 로그(해마다 1시간에 1번), 카운터 `market_calendar_uncovered_lookups_total{year}`.
- 게이지 `market_calendar_coverage_years_ahead`(올해부터 끊김 없이 채운 해 수 - 1, 올해도 없으면 -1). 알람 예: 10월 이후 `<= 0` → 내년 마이그레이션 필요.
- API 응답에 `calendarCovered`, `calendarCoverageUntil`, `uncoveredYears`. 웹 장 상태는 "휴장일 미확인"을 붙이고, 정산 화면은 "주말만 건너뛴 날짜"라고 알린다.
- DB 읽기 실패: 마지막 스냅샷 유지. 처음부터 실패면 빈 캘린더(= 전부 미커버)로 위 신호가 그대로 난다.

### API

- `GET /api/market/status` — `status`(OPEN·PRE·POST·CLOSED), `isTradingDay`, `holidayName`, `openAt/closeAt`, `nextOpen/nextClose`, 커버리지. 비로그인 공개.
  세션: 장전 08:30–09:00, 정규장 09:00–15:30, 장후 15:30–18:00. **그 해 첫 거래일은 10:00 개장**(개장식, 규칙으로 계산).
- `GET /api/market/calendar?from&to` — 평일 휴장일·영업일·미커버 해(최대 400일). 비로그인 공개.
- `GET /api/settlement/paper?status=PENDING|SETTLED|FAILED` — 서버 필터(모르는 값은 400).
- `GET /api/settlement/paper/summary?from&to` — 정산일 기준 기간 순액(매수 −, 매도 +, FAILED 제외), 일별·휴장일. 기본 이번 주(KST 월~일).

### 배치

- 모의·실거래 정산 배치와 포워드 테스트 일일 평가는 휴장일이면 건너뛴다(`MON-FRI` cron은 공휴일을 모른다).

### 이미 잡힌 정산일 (재정렬)

예전 규칙으로 잡힌 PENDING 정산일은 휴장일에 떨어졌을 수 있다(모의: `paper_settlements`, 실거래: `brokerage_settlements` — 실거래 정산일도
증권사 값이 아니라 우리가 계산한 값이다). **다시 계산해야 한다** — 두면 휴장일 정산이 원장에 남거나(실거래) 배치 건너뛰기로 하루씩 밀린다.

- SQL 마이그레이션이 아니라 기동 시 잡(`PaperSettlementDateRealigner`, `BrokerageSettlementDateRealigner`). 정산일 계산이 캘린더 코드에 있어 SQL로 복제하면
  사본이 하나 더 생기고, 캘린더가 바뀔 때(임시공휴일 추가)도 다시 돌아야 하기 때문이다. `ApplicationReadyEvent`와 `MarketCalendarChangedEvent`에 반응한다.
- 대상: `status='PENDING' AND settle_date >= 오늘(KST)`. 이미 지난 PENDING은 다음 정산 배치가 처리하므로 건드리지 않는다.
- 새 날짜 = 기준 시각(모의: `paper_trades.traded_at`, 실거래: 정산 행 `created_at` — 예전 계산의 기준과 같다)의 T+2. 새 날짜가 오늘보다 앞이면 옮기지 않는다.
- `UPDATE … WHERE id=? AND status='PENDING' AND settle_date=<읽은 값>` — 멱등, 다중 파드 동시 실행 안전, 그 사이 정산된 행은 건드리지 않는다.
- 정산일만 바뀐다. 현금·원장은 정산 시점에만 움직이므로 돈이 옮겨지지 않는다.

## Reasons

- 데이터 한 곳 + 메모리 스냅샷이라 정산 계산이 외부 장애에 묶이지 않고, 1년에 한두 번 바뀌는 데이터를 리뷰 가능한 마이그레이션으로 관리한다.
- 커버리지 분리 + 메트릭/응답 필드로 "모르는 해"를 실패가 아니라 **보이는 저하**로 만든다. 정산을 멈추는 것(대안)은 모의·실거래 모두 사용자 피해가 더 크다.
- 재정렬은 "읽은 값 그대로일 때만" 바꾸는 조건부 UPDATE라 락 없이도 다중 파드·정산 배치와 겹쳐도 안전하다.

## Consequences

- api·worker에 판단 규칙이 두 벌이다. 규칙을 바꿀 때 둘 다 바꿔야 한다(테스트가 각각 있다).
- worker `MarketSchedule.krCalendar`는 프로세스 전역 가변 스냅샷이다(`@Volatile`). 테스트는 끝나면 되돌려야 한다.
- 시드 정확성은 사람이 확인해야 한다. 틀린 날짜는 곧 틀린 정산일이다.
- 범위 밖:
  - **수능일 등 당국 공지에 따른 개장·마감 시간 변경**(예: 수능일 10:00 개장·16:30 마감). 날짜별 세션 예외 테이블이 필요해 다루지 않는다. 그날 장 상태 표시가
    1시간 어긋날 수 있다(정산일에는 영향 없음).
  - 미국 장 휴장일(worker `MarketSchedule` US 세션은 여전히 주말만).
  - 2025년 이전 데이터(리플레이에서 과거 날짜를 보면 미커버 경고가 난다).
  - NXT(대체거래소) 세션 구분.

## 운영 — 캘린더 갱신 절차

1. 매년 12월 KRX가 다음 해 휴장일을 공고하면(보통 12월 중순), 공고와 시드를 대조한다.
2. 후속 마이그레이션 `V{n}__krx_holidays_{year}.sql`: 틀린 행 수정·추가, 해당 해 `source='KRX_NOTICE'`, `market_calendar_years.verified=true`.
   다음 해를 새로 넣을 때는 휴장일 행과 `market_calendar_years` 행을 **같은 마이그레이션**에 넣는다.
3. 임시공휴일(정부가 수 일~수 주 전에 지정): 같은 방식의 마이그레이션(`source='TEMPORARY_HOLIDAY'`). 배포가 늦을 만큼 급하면 DBA가 같은 INSERT를
   직접 실행하고 마이그레이션은 `ON CONFLICT DO NOTHING`으로 뒤따른다. api·worker는 1시간 안에(`market-calendar.refresh-ms`) 다시 읽고, api는 바뀐 데이터로
   PENDING 정산일을 재정렬한다.
4. 알람: `market_calendar_coverage_years_ahead <= 0`이 11월 1일 이후 지속되면 2번을 하라는 뜻이다.

## Revisit When

- 수능일 등 세션 시간 예외를 화면·주문 검증에 정확히 반영해야 할 때(세션 예외 테이블 추가).
- 해외 주식 실거래를 열 때(NYSE·NASDAQ 캘린더를 같은 테이블의 `market`으로).
- 관리자 콘솔과 권한 모델이 생겨 운영자가 DB 접근 없이 임시공휴일을 넣어야 할 때.
- KRX가 휴장일을 기계가 읽을 수 있는 형태로 안정적으로 제공하면(자동 대조 잡).

## Notes

- 2026-10 — worker 관측성: worker `KrxHolidayCalendarLoader`도 api와 같은 이름·의미로 `market_calendar_coverage_years_ahead`(게이지, 이 프로세스의 스냅샷 기준)와
  `market_calendar_uncovered_lookups_total{year}`(카운터)를 내보낸다(커버리지 규칙은 `KrxHolidayCalendar.coverageYearsAhead`, 테스트가 Prometheus 노출 이름까지 본다).
  알람 `MarketCalendarNextYearMissing`·`MarketCalendarCurrentYearMissing`은 `job=~"monticker-api|monticker-worker.*"`를 job별 `min`으로 보고,
  `MarketCalendarUncoveredLookup`은 `{job, year}`로 나뉜다. 한 job만 울리면 그 프로세스가 DB를 읽지 못한 것이다([runbook](../runbooks/market-calendar.md)).
