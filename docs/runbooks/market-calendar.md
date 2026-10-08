# KRX 휴장일 캘린더 — `MarketCalendarNextYearMissing` · `MarketCalendarCurrentYearMissing` · `MarketCalendarUncoveredLookup` (ticket)

[ADR-086](../decisions/086-krx-trading-calendar.md). 영업일과 정산일(T+2)은 `market_holidays` 테이블로 계산한다.
`market_calendar_years`에 없는 해는 **주말만 휴장으로** 계산하고 멈추지 않는다. 그래서 데이터가 비면 장애 대신 조용히 틀린 날짜가 나간다.
이 알람들은 그걸 보이게 한다.

| 알람 | 식 | 뜻 |
|------|----|----|
| `MarketCalendarNextYearMissing` | `min by (job) (market_calendar_coverage_years_ahead{job=~"monticker-api\|monticker-worker.*"}) <= 0 and on() month() >= 11`, 1h | 11~12월인데 내년 캘린더가 없다. 1월 1일부터 틀린 날짜가 나간다 |
| `MarketCalendarCurrentYearMissing` | `min by (job) (market_calendar_coverage_years_ahead{job=~"monticker-api\|monticker-worker.*"}) < 0`, 15m | 올해 캘린더도 없다. **지금** 공휴일을 영업일로 세고 있다 |
| `MarketCalendarUncoveredLookup` | 지난 1h `market_calendar_uncovered_lookups_total{job, year}` 증가 또는 새 `{year}` 시계열 | 캘린더에 없는 해를 실제로 물었다. 틀린 답이 이미 나갔을 수 있다 |

게이지 값은 "올해부터 끊김 없이 채운 해 수 − 1"이다. 1이면 내년까지 있고, 0이면 올해만, −1이면 올해도 없다.
게이지와 카운터는 api(`MarketCalendar`)와 worker(`KrxHolidayCalendarLoader`)가 같은 이름·의미로 내보낸다. 값은 각 프로세스가 들고 있는 스냅샷 기준이다.
알람은 job별로 울린다. api는 정산일을, worker는 장 세션·틱 상태(모의 틱 생성, 지수 일봉)를 계산한다.
**모든 job이 같이 울리면 데이터 문제**(아래 조치), **한 job만 울리면 그 프로세스가 DB를 읽지 못한 것**이다(1차 확인 2번).

## 1차 확인
1. **어느 해가 비었나**: `SELECT year, verified, note FROM market_calendar_years WHERE market='KRX' ORDER BY year;`
2. **DB 문제가 아니라 데이터 문제인가**: 울린 job(api 또는 worker)의 로그 `market calendar has no KRX holiday data for year …`·`market calendar load failed`.
   캘린더를 읽다 실패하면 마지막 스냅샷을 유지한다. 그런데 기동 때부터 읽지 못했다면 빈 캘린더라서 올해도 미커버로 보인다(−1).
   이 경우 `market_calendar_years`에는 행이 있다. 원인은 DB 연결이다. [db-failover.md](db-failover.md)로 간다.
3. **UncoveredLookup의 `year` 라벨**:
   - 내년이면 연말 체결의 T+2가 새해로 넘어간 것이다. NextYearMissing과 같은 조치를 한다.
   - 과거 해면 오래된 데이터에 대한 계산(백테스트, 재정렬)이 범위 밖을 물은 것이다. 호출 경로를 확인한다. 정산에 쓰인 게 아니면 급하지 않다.

## 조치 — 휴장일 마이그레이션 (ADR-086 운영 절차)
1. KRX 휴장일 공고(보통 12월 중순)와 대조한다. 공고 전이면 법정 공휴일과 KRX 연말 휴장(12/31)으로 시드한다. `verified=false`로 둔다.
2. 마이그레이션 `V{n}__krx_holidays_{year}.sql`을 만든다. **휴장일 행과 `market_calendar_years` 행을 같은 파일에 넣는다.**
   공고로 확인한 해는 `source='KRX_NOTICE'`, `verified=true`로 둔다.
3. 배포 후 api·worker는 1시간 안에 다시 읽는다(`market-calendar.refresh-ms`). api는 `MarketCalendarChangedEvent`로 PENDING 정산일을 재정렬한다.
   돈은 옮기지 않는다. 정산일만 바뀐다.
4. 급하면(이미 해가 바뀌었고 CurrentYearMissing이 울렸다면) DBA가 같은 INSERT를 직접 실행한다. 마이그레이션은 `ON CONFLICT DO NOTHING`으로 뒤따른다.

## 확인
- 게이지가 1 이상(11~12월)이거나 0 이상(1~10월)으로 돌아오면 알람이 해소된다. `GET /api/market/calendar`의 `uncoveredYears`가 비어야 한다.
- UncoveredLookup은 카운터 증가가 멈추고 1시간이 지나면 해소된다.
- 미커버 기간에 잡힌 정산일은 재정렬이 고친다. 다만 **오늘 이전**으로 이미 지난 PENDING은 건드리지 않는다.
  그 기간에 정산 배치가 공휴일에 돌았는지 `paper_settlements`·`brokerage_settlements`에서 확인한다.

## 에스컬레이션
- CurrentYearMissing이 영업일 장중에 울리고 실거래 정산 행이 있다면 정산 담당자에게 알린다. 실거래 정산일은 증권사 값이 아니라 우리가 계산한 값이다.
