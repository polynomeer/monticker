# 브로커 서킷브레이커 OPEN — `BrokerCircuitOpen` (page) · `ExternalHttpSlow`

## 증상
- `resilience4j_circuitbreaker_state{name="kis"|"toss", state="open"} == 1` 30초 이상.
- 실주문 제출·취소·**잔고 조회**가 `503 ExternalServiceUnavailableException`("증권사 API 장애로 서킷브레이커가 열려 있습니다"). 사용자 화면: 잔고 카드 "잔고를 확인할 수 없습니다", 주문 폼 에러.
- Trading 대시보드 "브로커 느린 호출 / 실패 비율"이 50% 위.

## 영향 범위
- 실거래(BYOK) 사용자 전원 — 해당 증권사만. 모의투자는 무관.
- 조건부 주문(ADR-032)이 발동해도 제출이 503 → 조건부 주문 상태 확인 필요(`FAILED`로 남는지).
- 리밸런싱 미리보기·실행 503.
- 브레이커는 `waitDurationInOpenState` 뒤 HALF_OPEN → 시험 호출 → 성공하면 스스로 닫힌다. **수동 리셋은 기본적으로 불필요.**

## 1차 확인 (3단계)
1. **증권사 장애인가 우리 타임아웃인가**: 대시보드에서 `slow_call_rate` vs `failure_rate`. slow만 높으면 **지연**(P0-2: 5초 타임아웃, CH-06 재현), failure가 높으면 오류 응답/연결 거부.
2. **증권사 상태**: KIS 공지(`https://apiportal.koreainvestment.com`), Toss 개발자 공지. 장 운영 시간(KIS 모의투자 서버는 야간·주말 점검이 잦다).
3. **우리 쪽**: api 로그 `[CircuitBreaker:kis]` 전이 로그, `[KIS] 토큰 발급 실패`(앱키 만료?), 네트워크 egress.

## 완화
- 증권사 장애면 **기다린다**. 브레이커가 트래픽을 차단해 스레드 풀을 보호하고 있다(그게 존재 이유). 사용자 공지 문구:
  > "{증권사} 서버 응답 지연으로 실거래 주문·잔고 조회가 일시 중단됐습니다. 모의투자는 정상입니다. 복구 즉시 자동 재개됩니다."
- 우리 쪽 원인(토큰·키·설정)이면 고친 뒤 자연 복구를 기다린다(HALF_OPEN 시험 호출 2건).
- **수동 리셋을 고려할 때**: 증권사가 복구됐는데 HALF_OPEN 시험이 계속 실패(= 우리 쪽 잔존 문제). 리셋 전에 원인부터.

## 근본 원인 조사
- 타임아웃 5초가 증권사 정상 지연보다 짧은지: 장기 p95(`resilience4j_circuitbreaker_calls_seconds`).
- 반복되면 `slowCallDurationThreshold`·창 크기 조정은 [CircuitBreakerConfiguration](../../backend/api/src/main/kotlin/com/monticker/api/common/resilience/CircuitBreakerConfiguration.kt) — 실측 없이 늘리지 않는다.

## 복구 후 검증
- 브레이커 CLOSED, 잔고 카드 정상, 테스트 주문(모의투자 서버) 1건.
- 장애 중 발동한 조건부 주문·리밸런싱 실행 결과 확인.

## 에스컬레이션
- 장중 30분 이상 OPEN → 사용자 공지 확대(앱 배너), 증권사 기술 지원 문의.
