# ADR-109: 네이티브 앱은 refresh token을 본문으로 주고받고 OS 보안 저장소에 둔다

## Status
Accepted

웹의 refresh token 쿠키 결정([security-review.md](../security-review.md) C2 — HttpOnly·`Path=/api/auth` 쿠키로만 주고받는다)은 그대로다.
이 결정은 네이티브 앱용 경로를 따로 더한다. 회전·재사용 탐지(H3)는 웹과 같은 `AuthService`를 쓴다.

## Context

모바일 앱(`apps/mobile`, Expo)에는 로그인이 없었다. 관심종목·알림 규칙 화면은 토큰 없이 인증 API를 불러 늘 실패했다.
푸시 토큰 등록(`POST /api/devices/push-token`)도 인증 없이 보내 서버가 조용히 버렸다. 그래서 **모바일 기기는 한 번도 푸시 대상으로
저장된 적이 없다.** 통합 포트폴리오의 "내 계좌" 탭([account-aggregation-plan.md](../account-aggregation-plan.md) §8)과 보유 종목 알림
([ADR-108](108-holdings-as-notification-audience.md))도 로그인이 있어야 한다.

웹은 refresh token을 HttpOnly 쿠키로만 주고받는다. 네이티브 앱에서 이 방식을 쓸 수 있는지 봤다.

- (a) **쿠키를 그대로 쓴다.** React Native `fetch`는 플랫폼 쿠키 저장소(iOS `NSHTTPCookieStorage`, Android `CookieManager`)에
  기댄다. 동작이 플랫폼마다 다르다. 앱 재설치·OS 정리에 사라지고, 앱 코드가 저장 위치와 보호 수준을 정할 수 없다. `Secure` 쿠키는
  개발 환경(http)에서 저장되지 않는다.
- (b) **웹 경로가 헤더(예: `X-Client: native`)를 보면 본문으로도 준다.** 엔드포인트는 그대로다. 대신 헤더 하나로 웹에서도 본문
  토큰을 꺼낼 수 있다. 웹 페이지의 스크립트(XSS)가 쿠키 갱신 요청에 그 헤더를 붙이면, C2가 막으려던 "스크립트가 refresh token을
  손에 쥐는 것"이 다시 가능해진다.
- (c) **네이티브 전용 경로를 따로 둔다.** 쿠키를 읽지도 쓰지도 않고 본문으로만 주고받는다 — 채택.

## Decision

**서버** — `/api/auth/native/*` (`NativeAuthController`)

| 경로 | 요청 | 응답 |
|---|---|---|
| `POST /login` | `{email, password}` | `{accessToken, refreshToken, refreshExpiresAt}` · 실패는 401(사유 없음) |
| `POST /refresh` | `{refreshToken}` | 새 토큰 쌍(회전) · 폐기·만료·재사용은 401, 비었으면 400 |
| `POST /logout` | `{refreshToken}` | 204 — 그 토큰만 폐기 |

- 쿠키를 읽지도 굽지도 않는다. 웹 쿠키만으로는 이 경로에서 아무것도 얻을 수 없다. 본문 토큰을 얻으려면 비밀번호가 다시 필요하다.
- 회전·재사용 탐지는 `AuthService.refresh` 그대로다. 쓴 refresh token은 바로 폐기된다. 폐기된 토큰을 다시 쓰면 그 사용자의
  **모든** 세션이 끊긴다.
- 레이트리밋은 웹 로그인·갱신과 **같은 버킷**이다(`auth.login:{ip}`, `auth.refresh:{ip}`). 경로를 바꿔 한도를 두 배로 쓰지 못한다.
- 회원가입·비밀번호 찾기·소셜 로그인은 웹에서만 한다. 가입에는 동의 전문을 보여 주는 화면이 필요하다(ADR-068).
- **토큰 종류를 구분한다(보안 검토에서 추가).** access·refresh token은 같은 키·iss·aud로 서명돼 서명만으로는 구분되지 않았다.
  본문 경로가 생기면서 access token을 `/native/refresh`에 넣을 수 있게 됐다. 그러면 DB에 행이 없어 재사용 탐지가 돌고, 그 사용자의
  **모든 세션이 끊긴다** — access token을 가진 쪽(XSS가 읽은 웹 메모리 토큰, 로그 유출)이 피해자를 계속 로그아웃시킬 수 있었다.
  그래서 두 토큰에 `typ` 클레임(`access`·`refresh`)을 넣고, refresh·logout 경로는 refresh token만 받는다(`getRefreshUserId`).
  `typ` 이전에 발급된 토큰은 role 클레임 유무로 가른다(access에만 있다).
- 갱신의 확인과 폐기를 `DELETE … WHERE token_hash = ? AND user_id = ? AND expires_at > now()` 한 번으로 한다. 지운 행이 0이면
  재사용으로 본다. 예전에는 COUNT 뒤에 DELETE라 같은 토큰으로 동시에 두 번 갱신하면 둘 다 성공할 수 있었다.

**앱** — `apps/mobile/src/services/auth.ts`

- refresh token은 `expo-secure-store`(iOS Keychain·Android Keystore)에만 둔다. 보호 수준은 `WHEN_UNLOCKED_THIS_DEVICE_ONLY`다.
  기기 잠금이 풀린 동안만 읽히고, iCloud·기기 백업으로 다른 기기에 옮겨지지 않는다. access token은 메모리에만 둔다.
- 갱신은 한 번에 하나만 한다(single-flight). 여러 요청이 동시에 401을 받아 같은 토큰으로 두 번 갱신하면, 서버가 재사용으로 보고
  모든 세션을 끊는다.
- 갱신 결과가 401·400이면 저장소를 비우고 로그아웃 상태로 돌아간다. 네트워크 오류나 5xx·429면 토큰을 지우지 않는다(지하철에서 앱을
  열 때마다 로그아웃되지 않게).
- 푸시 토큰은 로그인한 뒤에 인증 요청으로 등록한다. 로그아웃할 때는 세션이 살아 있는 동안 먼저 해제하고, 그다음 refresh token을
  폐기한다.

## Reasons

- (c)는 웹의 C2 방어를 그대로 둔다. 웹 스크립트가 가진 쿠키로는 본문 토큰을 얻을 수 없다. (b)는 헤더 하나로 그 방어를 무너뜨린다.
- OS 보안 저장소는 하드웨어 기반 키로 암호화된다. 앱 샌드박스 밖에서 읽을 수 없고, 보호 수준을 앱이 정한다. 쿠키 저장소나
  AsyncStorage(평문 파일)는 둘 다 못 한다.
- 회전·재사용 탐지를 다시 구현하지 않는다. 웹과 같은 서비스·테이블을 쓰므로 "모든 세션 끊기"가 웹과 앱에 함께 적용된다.

## Consequences

- 인증 경로가 둘이 된다. 로그인 규칙(잠금·실패 횟수·탈퇴 계정)을 바꿀 때는 둘 다 같은 `AuthService`를 거치는지 확인한다.
- 탈취된 기기에서 잠금이 풀린 상태라면 refresh token을 쓸 수 있다. 기기 단위 세션 목록·원격 로그아웃은 없다. 지금은
  "모든 세션 끊기"(비밀번호 재설정, 재사용 탐지)만 있다.
- 앱이 백그라운드에서 갱신해야 하는 기능(백그라운드 새로 고침)이 생기면 `WHEN_UNLOCKED`로는 잠긴 기기에서 읽지 못한다.
- **회전 응답을 잃으면 모든 세션이 끊긴다.** 서버는 응답을 보내기 전에 옛 토큰을 지운다. 응답이 네트워크에서 사라지거나 앱이 그 순간
  종료되면, 기기에는 이미 지워진 토큰이 남는다. 다음 갱신이 재사용 탐지에 걸려 웹·다른 기기까지 모두 로그아웃된다. 웹의 동시 탭
  경쟁과 같은 비용이지만 모바일 네트워크에서는 더 자주 생길 수 있다. 잦으면 짧은 유예(직전 토큰을 몇십 초 동안 한 번 더 받기)를 둔다.
- 앱의 로그아웃은 진행 중인 갱신을 기다린 뒤 가장 새 토큰을 폐기한다. 로그아웃 뒤에 끝난 갱신이 받은 토큰은 저장하지 않는다.
  릴리스 빌드는 API·웹 주소가 https가 아니면 시작하지 않는다.
- 이 저장소에는 iOS 시뮬레이터·에뮬레이터 실행 환경이 없어서, 앱 쪽은 타입 검사와 서버 흐름 확인(curl)으로만 검증했다. 실제 기기에서
  로그인·재시작 복원·로그아웃을 확인해야 한다.

## Revisit When

- 회전 응답 유실로 "모든 기기에서 로그아웃"이 잦다는 신고가 오면 — 직전 토큰 유예를 둔다(같은 사용자·짧은 시간·한 번).
- 기기별 세션 목록이나 원격 로그아웃이 필요해지면 — refresh_tokens에 기기 식별자를 더한다.
- 생체 인증으로 앱 잠금을 걸 때 — `requireAuthentication` 옵션과 키 무효화(생체 정보 변경 시) 정책을 함께 정한다.
- 백그라운드 갱신이 필요해지면 — 보호 수준을 `AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY`로 낮출지 다시 본다.
- 소셜 로그인을 앱에 넣을 때 — 네이티브 OAuth(PKCE + 앱 링크) 흐름을 따로 정한다.
