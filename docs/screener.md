# Stock Screener — 설계 문서

> 토스증권 스타일의 실시간 종목 스크리너 홈화면

---

## 1. 화면 구조

```
┌─────────────────────────────────────────────────────────────────┐
│  [실시간 차트]  [급등·급락]  [외국인·기관 동향]                       │  ← 탭
├─────────────────────────────────────────────────────────────────┤
│  [전체] [국내] [해외]   ──   [거래대금▼] [거래량] [급상승] [급하락]    │  ← 필터
│  [실시간] [1일] [1주일] [1개월]                           토글: 위험주 │
├──┬─────────────┬────────┬───────┬────────┬────────┬────────────┤
│# │ 종목명       │ 현재가  │ 등락률 │ 거래대금│ 매수비율│ AI 요약    │  ← 헤더
├──┼─────────────┼────────┼───────┼────────┼────────┼────────────┤
│1 │ ▣ 삼성전자   │71,000  │ -1.2% │ 1.2조  │ ▓░░░░ │ 외국인...  │
│2 │ ▣ SK하이닉스 │180,000 │ +3.5% │ 8,400억│ ░▓░░░ │ 실적...    │
│… │             │        │       │        │        │            │
└──┴─────────────┴────────┴───────┴────────┴────────┴────────────┘
```

---

## 2. 탭 정의

| 탭 | 설명 | 정렬 기준 |
|---|---|---|
| **실시간 차트** | 거래대금/거래량 상위 종목 | 거래대금 순 |
| **급등·급락** | 등락률 상위/하위 종목 | 등락률 절댓값 |
| **외국인·기관 동향** | 외국인·기관 순매수 상위 | 순매수 금액 |

---

## 3. 필터

### 시장 필터
- 전체 / 국내(KOSPI+KOSDAQ) / 해외(NASDAQ+NYSE)

### 정렬 기준 (실시간 차트 탭)
- 거래대금 순 (기본)
- 거래량 순
- 급상승 (등락률 상위)
- 급하락 (등락률 하위)

### 기간 필터
- 실시간(기본) / 1일 / 1주일 / 1개월

### 토글
- 투자위험 주식 숨기기

---

## 4. 테이블 컬럼

| 컬럼 | 데이터 소스 | 표시 형식 |
|---|---|---|
| 순위 | 정렬 결과 | 숫자 |
| 종목명 | stocks.name | 로고 + 이름 + 심볼 |
| 현재가 | Redis / price_ticks | ₩ + 천단위 콤마 |
| 등락률 | (현재가-전일종가)/전일종가 | +/- % (빨강/파랑) |
| 거래대금 | candles_1m.volume×price | 억/조 단위 |
| 매수비율 바 | watchlist 집계 (mock) | 개인/외국인 bar |
| AI 요약 | Claude API | 한 줄 요약 |

---

## 5. API 설계

### 신규 엔드포인트

```
GET /api/screener?
  tab=realtime|movers|foreigners
  market=all|domestic|overseas
  sort=volume|amount|rise|fall
  period=realtime|1d|1w|1m
  limit=20
  offset=0
```

**응답 형태:**
```json
{
  "items": [
    {
      "rank": 1,
      "stockId": 2,
      "symbol": "005930",
      "name": "삼성전자",
      "market": "KOSPI",
      "price": 71000,
      "changeRate": -1.23,
      "changeAmount": -885,
      "volume": 12345678,
      "amount": 876543210000,
      "buyRatio": 42,
      "sellRatio": 58,
      "aiSummary": "외국인 매도세 지속...",
      "sector": "전기전자"
    }
  ],
  "total": 150,
  "updatedAt": "2026-06-23T10:43:00Z"
}
```

---

## 6. 컴포넌트 구조

```
src/app/screener/
  page.tsx                    ← 스크리너 홈 페이지 (Server Component)

src/components/screener/
  ScreenerTabs.tsx            ← 탭 네비게이션
  ScreenerFilters.tsx         ← 필터 바 (시장/정렬/기간)
  ScreenerTable.tsx           ← 종목 테이블
  ScreenerRow.tsx             ← 종목 행 (rank + logo + price + bar + summary)
  BuySellBar.tsx              ← 개인/외국인 매수비율 바
  ChangeRateBadge.tsx         ← 등락률 뱃지 (색상 분기)
  AiSummaryCell.tsx           ← AI 요약 (truncate + expand)

src/hooks/
  useScreener.ts              ← 스크리너 데이터 fetch + 10초 폴링
```

---

## 7. 백엔드 구현 계획

### backend/api — ScreenerModule

```
api/screener/
  domain/ScreenerItem.kt       ← 응답 도메인 모델
  infrastructure/
    ScreenerRepository.kt      ← JDBC 집계 쿼리
  application/
    ScreenerService.kt         ← 탭/필터/정렬 로직
  api/
    ScreenerController.kt      ← GET /api/screener
```

**핵심 쿼리 (거래대금 순):**
```sql
SELECT
  s.id, s.symbol, s.name, s.market, s.sector,
  c.close                                       AS price,
  c.volume                                      AS volume,
  c.close * c.volume                            AS amount,
  (c.close - prev.close) / prev.close * 100     AS change_rate
FROM stocks s
JOIN LATERAL (
  SELECT close, volume, candle_time
  FROM candles_1m
  WHERE stock_id = s.id
  ORDER BY candle_time DESC LIMIT 1
) c ON true
JOIN LATERAL (
  SELECT close FROM candles_1d
  WHERE stock_id = s.id
  ORDER BY candle_time DESC LIMIT 1 OFFSET 1
) prev ON true
WHERE s.is_active = true
ORDER BY amount DESC
LIMIT :limit OFFSET :offset
```

---

## 8. 구현 우선순위

| 단계 | 작업 | 예상 공수 |
|---|---|---|
| 1 | ScreenerController + Repository (거래대금 정렬) | 小 |
| 2 | ScreenerTable + ScreenerRow 기본 UI | 小 |
| 3 | BuySellBar + ChangeRateBadge | 小 |
| 4 | 필터 (시장/정렬) 동작 | 小 |
| 5 | 기간 필터 (1d/1w 집계) | 中 |
| 6 | 외국인·기관 동향 탭 | 中 |
| 7 | AI 요약 연동 | 中 |
| 8 | 실시간 폴링 + 애니메이션 | 中 |

---

## 9. 미결 사항

- [ ] 전일 종가 데이터 소스 — candles_1d에서 집계할지, 별도 daily_close 테이블 추가할지
- [ ] 매수/매도 비율 — Mock 데이터로 시작할지, watchlist 집계 기반으로 할지
- [ ] 로고 이미지 — 한국 주요 종목 로고 수집 방법
- [ ] 페이지네이션 vs 무한스크롤
- [ ] 모바일 레이아웃 (컬럼 축소 전략)
