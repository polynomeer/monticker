# monticker — Portfolio Write-up

## 한 줄 요약

주식 가격·거래량 이상을 실시간으로 탐지하고 이벤트 타임라인과 모바일 알림으로 전달하는
이벤트 중심 주식 관찰 앱.

## 문제 정의

기존 주식 앱들은 "현재가"를 나열하는 데 집중합니다. 개인 투자자가 진짜 필요한 것은
**"지금 무슨 일이 일어나고 있는가"** 입니다. monticker는 시장 이상을 자동으로 감지해
이벤트 타임라인으로 제공합니다.

## 핵심 기술 결정

### 1. 이벤트 중심 도메인 설계

`stock_events` 테이블을 중심 도메인으로 두고, 모든 가격·거래량 이상을 이벤트로 정규화합니다.
단순 조회형 앱과 달리 "왜 지금 이 종목이 움직이는가"를 기록합니다.

### 2. EMA 기반 이상 탐지

고정 임계값 대신 지수이동평균(EMA, α=0.1)으로 정규화된 변화율을 사용합니다.
시장 변동성에 적응적으로 동작하며, 시간대별 기준선 없이 온라인 학습이 가능합니다.

### 3. TimescaleDB

가격 틱(price_ticks)과 캔들(candles_1m/1d)을 TimescaleDB 하이퍼테이블로 관리합니다.
시계열 압축과 연속 집계(continuous aggregate)로 쿼리 성능을 확보합니다.

### 4. Modular Monolith

MSA 대신 단일 Spring Boot 애플리케이션 안에 모듈 경계를 명확히 유지합니다.
팀 규모와 MVP 단계에 적합하며, 필요시 서비스로 분리할 수 있는 경계를 보존합니다.

## 아키텍처 요약

```
Worker (1초 주기)
  → Mock/실시세 수집
  → Redis 캐시 갱신
  → EMA 이상 탐지
  → stock_events INSERT (분 단위 dedup)
  → alert_rules 평가 → alert_histories INSERT

API Server
  → REST: 종목/관심종목/시세/이벤트/알림
  → WebSocket (STOMP): 실시간 가격 브로드캐스트

Web (Next.js 15)
  → 종목 검색 → 상세 (차트 + 이벤트 타임라인 + 알림 설정)
  → Lightweight Charts + 이벤트 마커 오버레이

Mobile (Expo)
  → 관심종목 화면
  → Expo Push Notification 수신
```

## 주요 구현 포인트

| 항목 | 내용 |
|------|------|
| EMA 이상 탐지 | `VolumeSurgeDetector`, `PriceSpikeDetector` — α=0.1, 비율 임계값 |
| 이벤트 중복 방지 | `(stock_id, event_type, date_trunc('minute', event_time))` 유니크 인덱스 |
| 알림 쿨다운 | 동일 rule_id로 10분 내 중복 발송 차단 |
| 차트 이벤트 마커 | Lightweight Charts `setMarkers()` API, EventType별 색상·크기 |
| 테스트 전략 | 서비스 단위 테스트(MockK) + Controller MockMvc + Testcontainers 통합 |

## 개발 과정

Claude Code를 활용한 8주 개발 — 주차별 수직 슬라이스(Vertical Slice) 방식으로 진행했습니다.
매 주차 백엔드 도메인·API·Worker·웹 UI를 동시에 완성해 항상 동작하는 상태를 유지했습니다.

## 향후 계획

- KIS Developers API 연동 (실 시세)
- DART OpenAPI 공시 이벤트 수집
- Expo Push 실 발송 (EAS)
- JWT 인증
- Continuous Aggregate로 캔들 자동 집계
