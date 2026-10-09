# ADR-102: 주간 리포트 이메일은 로그인 없는 원클릭 수신 거부(RFC 8058)를 서명 토큰으로 지원한다

## Status
Accepted

## Context

ADR-101의 주간 투자 행동 리포트는 본문 링크와 `List-Unsubscribe` 헤더가 모두 `/settings/notifications`(로그인 필요)를 가리켰다.
ADR-101 Revisit에 적어 둔 "로그인 없는 한 번 클릭 수신 거부"가 필요해졌다:

- Gmail·Yahoo 대량 발송자 요건(2024~)은 `List-Unsubscribe` + `List-Unsubscribe-Post: List-Unsubscribe=One-Click`(RFC 8058)를 요구한다.
  로그인이 필요한 URL은 원클릭이 아니다. 지키지 않으면 스팸함으로 가거나 "수신 거부" 버튼 대신 "스팸 신고"가 눌린다.
- 정보통신망법 제50조는 수신 거부를 쉽게 할 수 있게 하라고 요구한다(로그인을 강제하면 다툼의 여지가 있다).

고려한 대안:

1. **DB에 저장하는 무작위 토큰**(사용자당 행, 발송마다 새 토큰) — 개별 폐기·만료가 쉽지만 마이그레이션·정리 잡·발송 경로의 쓰기가 늘어난다.
2. **무상태 HMAC 서명 토큰**(선택) — 저장소 없음. 검증은 서명 재계산뿐.
3. **JWT 재사용**(jwt.secret으로 서명) — 키를 섞으면 한쪽 유출·교체가 다른 쪽에 번진다(교체하면 로그인 세션 전부가 끊긴다). 기각.
4. **링크 GET으로 바로 끄기** — 메일 보안 스캐너(Outlook Safe Links, 사내 게이트웨이)가 링크를 미리 열어 보는 것만으로 꺼진다. 기각.

## Decision

### 토큰 — `common.notification.UnsubscribeTokenService`

```
v1.{scope}.{userId}.{issuedAtEpochSec}.{base64url(HMAC-SHA256(secret, "monticker-unsubscribe|" + 앞 네 부분))}
예) v1.weekly_report.42.1760313600.Xq3…(43자)
```

- 키: `app.unsubscribe.secret` / env `UNSUBSCRIBE_TOKEN_SECRET`. **최소 32바이트**(미만이면 기동 실패), **jwt.secret과 달라야 함**.
  `application.yml`의 개발 기본값은 공개 값이라 `InsecureSecretGuard`가 기동을 막는다(`ALLOW_INSECURE_DEV_SECRETS=true`일 때만 허용,
  docs/security-review.md C1과 같은 방식). `application-prod.yml`은 기본값 없이 필수.
- 범위(`scope`)를 서명에 넣는다. 지금은 `weekly_report` 하나다. 다른 범위로 서명된 토큰으로 주간 리포트를 끌 수 없다.
- 비교는 **인코딩된 서명 문자열끼리 상수 시간**(`MessageDigest.isEqual`). 디코딩한 바이트를 비교하면 마지막 문자의 남는 비트만 다른
  변형도 통과하므로 문자열로 비교한다. 형식(정규식·길이 160자)은 서명 계산 전에 거른다.
- **만료 없음.** 수신 거부 링크는 몇 달 지난 메일에서 눌러도 동작해야 한다(CAN-SPAM은 발송 후 최소 30일 유효를 요구하고, 대부분의
  발송 서비스가 만료 없는 링크를 쓴다). 링크가 새도 할 수 있는 일은 "그 사용자가 주간 리포트를 받지 않게 되는 것"뿐이고 사용자는
  설정에서 다시 켤 수 있다. 전체 무효화는 시크릿 교체로 한다. `issuedAt`은 나중에 "이 시각 이전 발급분 거부"를 시크릿 교체 없이
  하려고 남긴다(지금은 검사하지 않는다).
- 토큰에 **이메일은 없다**. 내부 사용자 id(숫자)는 평문이다 — 아래 Consequences.

### 엔드포인트 — `POST /api/unsubscribe?token=…` (auth 모듈 `UnsubscribeController`)

- `SecurityConfig`에서 `permitAll`(로그인 없음). CSRF는 전역으로 꺼져 있다(JWT 헤더 인증). 쿠키 세션을 쓰지 않으므로 CSRF로 얻을 것도 없다
  — 남의 브라우저로 이 POST를 보내게 해도 공격자가 이미 가진 토큰의 효과밖에 없다.
- 본문 `List-Unsubscribe=One-Click`(form)은 받지만 **요구하지 않는다**. 판단은 서명만으로 한다.
- **GET 매핑은 없다**(405). 사람이 누르는 본문 링크는 웹 확인 화면(아래)으로 간다.
- 서명이 맞으면 `NotificationPreferenceService.disableWeeklyReportEmail(userId)` — 주간 리포트 발송 판정(ADR-101 후보 SQL)이 보는
  `notification_preferences.weekly_report_email`만 `false`로 바꾼다. 다른 설정은 건드리지 않는다.
  - 행이 있으면 그 컬럼만 원자적으로 UPDATE(설정 화면 저장과 경합해도 다른 필드를 덮지 않음). 이미 꺼져 있으면 아무것도 바꾸지 않는다.
  - 행이 없으면(ADR-082 지연 이전 전) 옛 Redis 설정을 바탕으로 행을 만든다. Redis를 못 읽으면 **503**으로 실패한다 — 기본값(전부 켜짐)으로
    덮어 사용자가 꺼 둔 다른 알림을 되살리지 않기 위해서다. 메일 클라이언트·사용자가 다시 시도한다.
- 응답: 성공 `200 {"status":"unsubscribed"}`(`Cache-Control: no-store`), 사용자 정보 없음. 여러 번 보내도 같은 응답(멱등).
- 서명이 틀린·잘린·없는 토큰은 DB를 보기 전에 **같은 모양의 400**(`"유효하지 않은 수신 거부 링크입니다"`, detail 없음).
  서명 검증이 사용자 조회보다 먼저라 응답이 사용자 존재 여부와 무관하다(H6 원칙).
- **탈퇴했거나 없는 사용자**인데 서명이 맞는 토큰 → **성공과 같은 200**(아무것도 안 함). 탈퇴자에겐 어차피 보내지 않고(ADR-101),
  다른 응답을 주면 링크를 가진 사람이 탈퇴 여부를 알 수 있다.
- 레이트리밋: `RateLimitFilter`의 `/api` 공통 IP 버킷(분당 300)을 그대로 쓴다. 메일 사업자는 소수의 공용 IP에서 대량으로 POST하므로
  더 좁은 전용 버킷을 두면 정상 수신 거부가 429로 떨어진다. 서명 검증이 먼저라 무차별 대입으로 얻을 것이 없다(256비트 HMAC).
- 지표 `email_unsubscribe_total{scope, result=changed|noop|invalid}`. 로그는 실제로 꺼진 경우만 userId로 남긴다.

### 메일 (`WeeklyBehaviorReportJob`)

```
List-Unsubscribe: <{APP_BASE_URL}/api/unsubscribe?token=…>
List-Unsubscribe-Post: List-Unsubscribe=One-Click
```

- mailto는 넣지 않는다 — 수신 거부 메일을 처리할 받은편지함이 없다.
- 본문의 수신 거부 링크는 **웹 확인 화면** `{APP_BASE_URL}/unsubscribe?token=…`. 알림 설정 링크는 보조로 남긴다.
- 토큰은 발송마다 그 사용자에게 새로 서명한다(저장하지 않는다).

### 웹 확인 화면 (`apps/web` `/unsubscribe`)

- 로그인 없이 연다. **열기만 해서는 아무것도 하지 않고**, "수신 거부" 버튼을 눌러야 같은 POST를 보낸다(`credentials: "omit"`,
  Authorization 없음). 결과: 완료 / 잘못된 링크(400) / 다시 시도(그 밖) — 실패 화면은 알림 설정 링크로 안내한다.
- `robots: noindex`, `referrer: no-referrer` — 주소에 토큰이 있다.

### 경로 이름

요청 초안은 `/api/v1/unsubscribe`였지만 이 API에는 버전 접두어가 없다(전부 `/api/...`). 하나만 다르게 두지 않고 `/api/unsubscribe`로 했다.
Next.js `/api/:path*` 리라이트와 Ingress `/api` 라우팅을 그대로 탄다.

## Reasons

- 저장소가 없어 마이그레이션·정리 잡·발송 경로의 쓰기가 없다. 발송량이 늘어도 비용이 없다.
- 키 분리 + 범위 서명 + 상수 시간 비교 + 부팅 가드로, 토큰 위조는 키 유출 없이는 불가능하다.
- GET은 화면, POST만 변경이라 메일 스캐너의 미리 열기로 꺼지지 않는다. 메일 클라이언트의 원클릭은 RFC 8058 그대로 동작한다.
- 응답 모양을 "유효/무효" 두 가지로만 두어 사용자 존재·탈퇴 여부가 드러나지 않는다.

## Consequences

- **개별 토큰을 폐기할 수 없다.** 링크가 새면(메일 전달 등) 받은 사람이 그 사용자의 주간 리포트를 끌 수 있다. 피해는 리포트 미수신뿐이고
  설정에서 다시 켤 수 있다. 전체 폐기는 시크릿 교체(그 뒤엔 이미 보낸 메일의 원클릭 링크가 400 — 알림 설정 링크는 계속 동작).
- **토큰에 내부 사용자 id가 평문으로 있다.** 링크를 본 사람은 숫자 id를 알 수 있다. 그 id로 할 수 있는 다른 일은 없다(모든 사용자 리소스
  API는 JWT 주체로 소유를 확인한다). 숨기려면 암호화(AES-GCM) 토큰이 필요하다 — 지금은 복잡도 대비 이득이 작다고 판단.
- 새 필수 시크릿이 하나 늘었다: 배포 시 `UNSUBSCRIBE_TOKEN_SECRET` 발급(`openssl rand -base64 48`) — `infra/k8s/base/secret.yaml`,
  External Secrets 예시, e2e CI에 추가했다. 없으면 prod 프로파일은 기동하지 않는다.
- 옛 Redis 설정만 있는 사용자는 Redis 장애 중 수신 거부가 503이다(드문 경우, 다시 시도하면 된다).
- 메일 사업자의 원클릭 POST도 IP 공통 버킷(분당 300)에 걸린다. 한 사업자 IP에서 분당 300건을 넘기면 일부가 429다(사업자는 재시도한다).

## Revisit When

- 링크 유출로 원치 않는 수신 거부가 실제로 신고될 때 — `issuedAt` 컷오프나 DB 토큰(개별 폐기)을 검토한다.
- 다른 이메일 종류(광고성 전략 마켓 소식 등)에 수신 거부를 붙일 때 — `UnsubscribeScope`에 범위를 더하고 엔드포인트에서 범위별로 끈다.
- 수신 거부 메일 받은편지함이 생길 때 — `List-Unsubscribe`에 mailto를 더한다.
- 원클릭 POST가 메일 사업자 IP 레이트리밋에 실제로 걸릴 때 — 이 경로만 별도 버킷이나 예외로 뺀다.
