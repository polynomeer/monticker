# API 벤치마크 디버깅 포스트모템

**작성일**: 2026-06-24  
**대상**: API 성능 테스트를 처음 도입하는 팀의 엔지니어  
**요약**: k6로 monticker API 벤치마크를 구축하는 과정에서 97% 실패율 → 97% 실패율 → 25% 실패율 순으로 세 번의 연속된 장애를 겪었다. 각 단계에서 원인이 달랐으며, 이 문서는 그 조사 과정과 해결책을 기록한다.

---

## 1. 개요 — 왜 벤치마크를 도입했는가

monticker는 실시간 주가 데이터를 다루는 서비스다. 스크리너, 이벤트 타임라인, 종목 검색 등 주요 API가 동시 접속 상황에서도 일정한 응답 시간을 유지해야 한다는 요건이 있었다. 기능 구현이 어느 정도 안정화된 시점에서 다음 질문에 답해야 했다.

- p95 응답 시간이 목표값(스크리너 200ms, 종목 검색 300ms) 안에 드는가?
- 50 VU 부하에서 에러율이 1% 미만인가?
- 급격한 트래픽 급증(spike) 상황에서 서버가 graceful하게 처리하는가?

이 질문들에 답하기 위해 k6를 선택했다. k6는 JavaScript 기반의 시나리오 작성, 커스텀 메트릭, HTML 리포트 등을 지원하며 CI 파이프라인에 통합하기도 쉽다.

---

## 2. k6 기초 — VU, 시나리오, 임계값

### VU (Virtual User)

k6에서 VU(Virtual User)는 독립적인 HTTP 클라이언트처럼 동작한다. 각 VU는 자체 쿠키 jar, 연결 풀, 상태를 갖는다. `stages`로 VU 수를 시간에 따라 조절하며 부하를 점진적으로 높이거나 낮출 수 있다.

```javascript
stages: [
  { duration: "30s", target: 10  },  // 30초 동안 10 VU까지 ramp-up
  { duration: "1m",  target: 50  },  // 1분 동안 50 VU 유지
  { duration: "30s", target: 0   },  // 30초 동안 ramp-down
]
```

### 시나리오 4종

monticker 벤치마크는 목적에 따라 4개의 시나리오를 정의한다.

| 시나리오 | 목적 | 최대 VU | 소요 시간 |
|----------|------|---------|----------|
| `smoke`  | 기본 동작 확인 | 3 | 1분 |
| `load`   | 일반 부하 검증 | 50 | 3분 30초 |
| `stress` | 한계 탐색 | 100 | 5분 |
| `spike`  | 급격한 트래픽 대응 | 100 | 70초 |

### 임계값 (Thresholds)

임계값은 테스트 합격/실패 기준이다. k6는 임계값 위반 시 비제로 종료 코드를 반환하므로 CI에서 자동으로 실패 처리된다.

```javascript
thresholds: {
  "http_req_failed":   ["rate<0.01"],    // 에러율 1% 미만
  "http_req_duration": ["p(95)<300", "p(99)<500"],
  "screener_duration": ["p(95)<200"],    // 커스텀 메트릭
}
```

---

## 3. 1차 조사: 97% 실패율 — 쿠키 jar 오염

### 증상

smoke 테스트를 처음 실행했을 때 `http_req_failed` 비율이 97%에 달했다. 터미널 출력은 다음과 같았다.

```
     checks.........................: 3.00%  9 out of 300
     ✗ status 200
       ↳  3% — 9 / 300
     ✗ 응답시간 < 300ms
       ↳  3% — 9 / 300
```

### 가설 수립

처음에는 서버 측 문제를 의심했다. Spring Boot 로그를 확인했지만 예외가 없었고 서버는 정상이었다. 다음으로 k6 스크립트 자체를 살펴봤다.

k6의 VU는 기본적으로 **쿠키를 자동으로 관리**한다. 한 VU가 인증 엔드포인트를 호출해 `JSESSIONID`를 발급받으면, 이후 동일한 iteration에서 그 쿠키가 모든 요청에 함께 전송된다. 문제는 **VU 재사용** 시나리오에서 발생했다. VU가 테스트 도중 재활용될 때 이전 iteration의 쿠키가 그대로 남아 있었고, 만료되거나 잘못된 `JSESSIONID`가 이후 요청에 오염(contamination)되어 401 Unauthorized를 유발했다.

### 검증

단일 VU로 `/api/stocks/search`를 반복 호출하면서 요청 헤더를 로깅하니, 첫 번째 요청은 정상이었지만 두 번째 요청부터 `Cookie: JSESSIONID=...` 헤더가 붙어 있었다. 해당 세션이 서버에서 이미 만료된 상태였으므로 401이 반환됐다.

### 해결

각 iteration 시작 시점에 해당 origin에 대한 쿠키 jar를 명시적으로 비운다.

```javascript
export default function () {
  // 매 iteration 쿠키 초기화 (인증 쿠키 오염 방지)
  http.cookieJar().clear(BASE);
  // ...
}
```

`http.cookieJar().clear(BASE)`는 k6의 글로벌 쿠키 jar에서 지정한 origin에 해당하는 모든 쿠키를 삭제한다. 이 한 줄 추가로 VU 재사용 시 상태가 격리된다.

---

## 4. 2차 조사: 여전히 97% 실패 — Rate Limiter가 작동하고 있었다

### 증상

쿠키 문제를 수정한 뒤 재실행했는데 실패율이 동일했다. 에러 내용을 자세히 보니 이전과 미묘하게 달랐다. k6의 기본 출력에서는 4xx를 모두 실패로 동일하게 처리하기 때문에 401과 429가 구분되지 않았다. `res.status`를 직접 출력해보니 대부분이 `429`였다.

### hey로 교차검증

k6 외부에서 상태 코드를 직접 확인하기 위해 `hey`를 사용했다.

```bash
hey -n 200 -c 10 http://localhost:8080/api/screener?tab=realtime
```

출력 결과:

```
Status code distribution:
  [200]  12 responses
  [429] 188 responses
```

Rate Limiter가 작동 중이었다. 서버는 정상 동작하고 있었고, 문제는 벤치마크 자체가 허용 한도를 즉시 초과하고 있다는 점이었다.

### Rate Limiter 구조 분석

`RateLimitFilter`는 Redis 카운터를 사용해 IP별로 분당 요청 수를 제한한다.

```kotlin
val (key, limit) = when {
    path.startsWith("/api/auth/") -> "auth:$ip" to 20
    path.startsWith("/api/")      -> "api:$ip"  to 120
    else                          -> { chain.doFilter(req, res); return }
}
```

로컬 머신에서 k6가 `localhost`(127.0.0.1)에서 요청을 보내면 모든 VU가 동일한 IP로 집계된다. 3 VU x 초당 약 3~4 요청 = 분당 수백 건이 단일 `rate:api:127.0.0.1` 키에 누적되어 120 한도를 수초 내에 초과했다.

### 해결: X-Bench 화이트리스트

프로덕션 Rate Limiter 설정을 건드리지 않으면서 벤치마크 요청만 우회하는 방법으로 요청 헤더를 활용했다.

k6 스크립트:
```javascript
const HEADERS = { "X-Bench": "true" };

// 모든 요청에 헤더 추가
const res = http.get(`${BASE}/api/screener?...`, { headers: HEADERS });
```

서버 `RateLimitFilter`:
```kotlin
// 벤치마크 요청은 rate limit 제외
if (req.getHeader("X-Bench") == "true") {
    chain.doFilter(req, res)
    return
}
```

또한 `bench/run.sh`에서는 테스트 시작 전 Redis에 쌓인 기존 카운터를 초기화한다.

```bash
REDIS_CONTAINER=$(docker ps --format "{{.Names}}" | grep -i redis | head -1)
if [ -n "$REDIS_CONTAINER" ]; then
  docker exec "$REDIS_CONTAINER" redis-cli KEYS "rate:*" | \
    xargs -r docker exec "$REDIS_CONTAINER" redis-cli DEL > /dev/null 2>&1 || true
fi
```

**설계 원칙**: `X-Bench` 헤더는 프로덕션 환경에서 검증 없이 통과시키면 보안 취약점이 될 수 있다. 이 프로젝트에서는 해당 헤더를 로컬/스테이징 전용으로 제한하고, 프로덕션에서는 화이트리스트 로직 자체를 비활성화하거나 신뢰할 수 있는 내부 IP 대역에서만 허용하도록 별도 설정이 필요하다.

---

## 5. 3차 조사: 25% 실패 — 누락 엔드포인트와 SQL alias 문제

### 증상

쿠키와 Rate Limiter 문제를 모두 해결한 뒤 성공률이 크게 올랐지만, 여전히 25% 내외의 실패가 발생했다. 이번에는 상태 코드 분포를 먼저 확인했다.

```
Status code distribution:
  [200] 225 responses
  [404]  50 responses
  [500]  25 responses
```

### Actuator 메트릭으로 상태 코드 분포 확인

Spring Boot Actuator의 `http.server.requests` 메트릭을 조회하면 엔드포인트별 상태 코드 분포를 확인할 수 있다.

```bash
# 전체 상태 코드 목록
curl -s http://localhost:8080/actuator/metrics/http.server.requests \
  | jq '.availableTags[] | select(.tag == "status") | .values'

# 404가 발생한 URI 필터링
curl -s "http://localhost:8080/actuator/metrics/http.server.requests?tag=status:404" \
  | jq '.availableTags[] | select(.tag == "uri") | .values'
```

`/api/events/recent` 경로에서 404가 집중됐고, `/api/screener`의 일부 요청에서 500이 발생하고 있었다.

### 문제 1: 누락된 엔드포인트

`/api/events/recent`는 홈 화면에서 사용하는 엔드포인트였다. 웹 브라우저에서는 정상 동작하는 것처럼 보였는데, 이는 Next.js의 클라이언트 측 에러 처리가 404를 조용히 무시하고 있었기 때문이다. 실제로 컨트롤러에 해당 라우트가 등록되어 있지 않았다. 엔드포인트를 추가해 해결했다.

벤치마크가 없었다면 이 누락은 오랫동안 발견되지 않았을 것이다. 프론트엔드에서 에러가 시각적으로 드러나지 않는 경우, API 레벨의 자동화된 검증이 반드시 필요하다.

### 문제 2: SQL alias를 ORDER BY에서 직접 참조

`/api/screener` 500 에러는 서버 로그를 통해 원인을 찾았다.

```
ERROR: column "change_rate" does not exist
  Hint: Perhaps you meant to reference the column "s.change_rate".
  Position: 487
```

SELECT 절에 정의한 alias를 ORDER BY에서 직접 사용했는데, 이는 서브쿼리나 집계 컨텍스트에서 PostgreSQL이 alias를 인식하지 못하는 경우다. 수정 전:

```sql
SELECT s.*, (s.close - s.prev_close) / s.prev_close * 100 AS change_rate
FROM stocks s
ORDER BY change_rate DESC  -- alias 직접 참조: 컨텍스트에 따라 오류 발생
```

수정 후 (표현식 직접 사용):

```sql
SELECT s.*, (s.close - s.prev_close) / s.prev_close * 100 AS change_rate
FROM stocks s
ORDER BY (s.close - s.prev_close) / s.prev_close * 100 DESC
```

또는 서브쿼리로 감싸는 방법:

```sql
SELECT * FROM (
  SELECT s.*, (s.close - s.prev_close) / s.prev_close * 100 AS change_rate
  FROM stocks s
) sub
ORDER BY change_rate DESC
```

이 문제 역시 프론트엔드 스크리너에서는 특정 정렬 조건 조합에서만 발생했고, 벤치마크가 무작위 `sort` 파라미터를 전송했기 때문에 발견할 수 있었다.

---

## 6. 최종 결과 — 0% 에러율

세 문제를 모두 수정한 뒤 load 시나리오(최대 50 VU) 기준 결과:

```
     data_received..................: 2.1 MB  12 kB/s
     data_sent......................: 420 kB  2.4 kB/s
     http_req_duration..............: avg=45ms  min=8ms   med=38ms  max=312ms p(90)=89ms  p(95)=118ms
     http_req_failed................: 0.00%   0 out of 2847
     screener_duration..............: avg=62ms  min=12ms  med=54ms  max=289ms p(90)=112ms p(95)=148ms
     stocks_duration................: avg=28ms  min=5ms   med=22ms  max=198ms p(90)=58ms  p(95)=78ms
     events_duration................: avg=19ms  min=3ms   med=15ms  max=134ms p(90)=42ms  p(95)=56ms
     recent_events_duration.........: avg=14ms  min=2ms   med=11ms  max=98ms  p(90)=31ms  p(95)=44ms
     success_rate...................: 100.00% 711 out of 711

  ✓ http_req_failed.......: rate<0.01
  ✓ http_req_duration.....: p(95)<300, p(99)<500
  ✓ screener_duration.....: p(95)<200
  ✓ stocks_duration.......: p(95)<150
  ✓ events_duration.......: p(95)<150
```

모든 임계값 통과. `http_req_failed` 0.00%.

---

## 7. 벤치마크 시스템 설계

### 디렉토리 구조

```
bench/
  scenarios/
    api.js          # k6 메인 스크립트
  run.sh            # 래퍼 셸 스크립트
  reports/          # HTML 리포트 (gitignore)
  results/          # JSON 원본 데이터 (gitignore)
```

### 시나리오 선택

각 시나리오는 `--env SCENARIO=<name>` 환경 변수로 선택한다. `run.sh`가 이를 추상화한다.

```bash
./bench/run.sh          # smoke (기본값)
./bench/run.sh load
./bench/run.sh stress
./bench/run.sh spike
./bench/run.sh load --dashboard   # 실시간 k6 웹 대시보드 포함
```

`--dashboard` 플래그를 사용하면 `http://127.0.0.1:5665`에서 실시간 차트를 확인할 수 있다.

### 커스텀 메트릭

기본 `http_req_duration`은 모든 엔드포인트를 합산하므로 병목을 특정하기 어렵다. 엔드포인트별 Trend 메트릭을 별도로 정의했다.

```javascript
const screenerDuration = new Trend("screener_duration", true);
const stocksDuration   = new Trend("stocks_duration",   true);
const eventsDuration   = new Trend("events_duration",   true);
const recentDuration   = new Trend("recent_events_duration", true);
const errorCount       = new Counter("error_count");
const successRate      = new Rate("success_rate");
```

`Trend` 두 번째 인자 `true`는 밀리초 단위를 명시한다. 이 메트릭들은 임계값에도 직접 사용할 수 있어 특정 엔드포인트만 실패 기준을 세분화할 수 있다.

### handleSummary — HTML 리포트와 JSON 저장

```javascript
export function handleSummary(data) {
  const ts       = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
  const scenario = __ENV.SCENARIO || "smoke";

  return {
    [`bench/reports/${scenario}_${ts}.html`]:      htmlReport(data),
    [`bench/results/${scenario}_${ts}.json`]:      JSON.stringify(data, null, 2),
    [`bench/results/latest_${scenario}.json`]:     JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: "  ", enableColors: true }),
  };
}
```

타임스탬프가 붙은 파일로 이력을 보존하면서, `latest_<scenario>.json`을 항상 덮어써 CI가 최신 결과를 쉽게 참조할 수 있게 한다. HTML 리포트는 [benc-uk/k6-reporter](https://github.com/benc-uk/k6-reporter)를 사용해 그래프와 요약 테이블을 생성한다.

---

## 8. 교훈과 체크리스트

### 교훈

**에러 코드를 구분하라.** k6의 `http_req_failed`는 4xx와 5xx를 동일하게 처리한다. 401, 429, 404, 500은 원인이 전혀 다르다. 테스트 스크립트에서 `res.status`를 직접 로깅하거나 `check` 조건을 세분화해야 한다.

**외부 도구로 교차검증하라.** k6만 사용하면 k6 자체의 동작(쿠키 jar 등)이 원인인지 서버 문제인지 구분하기 어렵다. `hey`, `curl`, `httpie` 등 다른 도구로 동일한 요청을 보내 비교하는 습관이 필요하다.

**벤치마크는 API 테스트이기도 하다.** 성능뿐 아니라 엔드포인트 존재 여부와 SQL 정확성까지 검증됐다. 특히 프론트엔드에서 에러를 조용히 무시하는 코드가 있을 때 API 레벨 자동화는 필수다.

**Rate Limiter 설계 시 테스트 환경을 고려하라.** 로컬에서 부하 테스트를 실행하면 단일 IP로 대량 요청이 집중된다. 운영 정책을 변경하지 않고도 테스트를 허용하는 메커니즘(헤더 화이트리스트, 전용 IP 범위 등)을 처음부터 설계에 포함하라.

### 벤치마크 도입 체크리스트

- [ ] k6 설치 확인 (`brew install k6`)
- [ ] smoke 테스트로 먼저 실행, 에러율 확인
- [ ] 각 실패 요청의 상태 코드를 개별 확인 (401/404/429/500 구분)
- [ ] 외부 도구(hey, curl)로 서버 직접 검증
- [ ] Rate Limiter가 있으면 벤치마크 우회 방법 준비
- [ ] 커스텀 메트릭으로 엔드포인트별 p95 측정
- [ ] 임계값 설정 후 CI에 연동
- [ ] HTML 리포트 경로를 PR 코멘트나 아티팩트로 보존
