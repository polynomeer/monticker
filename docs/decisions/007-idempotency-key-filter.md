# ADR-007: Idempotency Key Filter for Mutating Order Endpoints

## Status
Accepted

## Context

모바일 클라이언트는 불안정한 네트워크 환경에서 타임아웃 후 동일 요청을 재전송할 수 있다.  
`POST /api/paper/buy`, `/api/paper/sell`, `/api/matching/orders`는 각각 현금 차감 또는 주문 생성을 수행하므로 중복 실행은 사용자 잔고에 직접적인 금전적 영향을 준다.

DB 레벨의 유니크 제약으로는 해결하기 어렵다 — 두 요청이 서로 다른 시간에 도착하면 각각 별도 레코드로 삽입된다.

## Decision

HTTP 레이어에서 `X-Idempotency-Key` 헤더를 기반으로 중복 요청을 Redis에 캐시해 동일 응답을 반환하는 `OncePerRequestFilter`를 도입한다.

```
요청 수신 (POST /api/paper/buy + X-Idempotency-Key: {uuid})
  │
  ├─ Redis HIT → 캐시된 응답 반환 (DB 접근 없음)
  └─ MISS → 요청 처리 → 200~299 응답 시 Redis 저장 (TTL 24h)

캐시 키: "idempotency:{userId}:{X-Idempotency-Key}"
```

Redis 키를 `userId`로 네임스페이스 분리해 다른 사용자의 키와 충돌을 방지한다.  
응답 실패(4xx/5xx)는 캐시하지 않아 클라이언트가 재시도할 수 있다.

## Reasons

- **서블릿 필터 레이어**에서 처리함으로써 비즈니스 로직 변경 없이 보호를 적용한다.
- Redis TTL 24시간은 모바일 앱의 재시도 윈도우(일반적으로 수 분)를 충분히 커버하면서 메모리 낭비를 방지한다.
- `ContentCachingResponseWrapper`로 응답 바디를 캡처해 동일 HTTP 상태 코드와 바디를 재현한다.
- `userId` 네임스페이싱으로 키 탈취 공격(다른 사용자의 키를 재사용해 타 사용자 응답 획득)을 차단한다.

## Consequences

- 클라이언트가 `X-Idempotency-Key`를 생성·전달해야 한다. 헤더가 없으면 중복 보호가 없다 (처리는 정상 진행).
- Redis 장애 시 캐시 조회가 실패하면 중복 보호 없이 요청이 통과된다 (fail-open). 금전적 리스크보다 가용성을 우선하는 모의투자 맥락에서 허용 가능한 트레이드오프다.
- 응답 바디 전체를 Redis에 저장하므로 대용량 응답이 있는 엔드포인트에는 주의가 필요하다 (현재 대상 엔드포인트는 모두 소형 JSON).

## Revisit When

- 실제 증권 주문(모의투자가 아닌 실거래)으로 확장될 때 → fail-open 정책을 fail-closed로 변경해야 한다.
- `X-Idempotency-Key`를 서버가 발급하는 방식(PRG 패턴)으로 전환할 때.
