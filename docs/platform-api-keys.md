# 플랫폼 KIS/Toss 앱키 발급 절차

실시간 시세 수집([ADR-030](decisions/030-kis-realtime-tick-ingestion.md), [ADR-031](decisions/031-toss-realtime-tick-ingestion.md))에 필요한 **플랫폼 레벨** KIS/Toss 앱키를 발급받아 `backend/worker`에 설정하는 절차다.

## 플랫폼 키 vs BYOK 키 — 반드시 구분할 것

이 문서는 **플랫폼 키**(monticker 운영자 명의 앱 하나가 202종목 전체 시세를 모든 사용자에게 공급) 발급 절차다. 사용자가 각자 자기 명의로 연동하는 **BYOK 브로커리지 키**(주문 실행, [ADR-025](decisions/025-real-brokerage-order-safety-gate.md)~[028](decisions/028-brokerage-order-cancellation.md))와는 완전히 다른 것이며, 특히 Toss는 **환경변수 이름 자체가 다르다**(아래 참고). 두 키를 혼동하면 안 된다.

| | 플랫폼 키 (이 문서) | BYOK 키 (사용자 개인) |
|---|---|---|
| 용도 | 시세 수집 → `market.ticks` (전 사용자 대상) | 주문 실행 → 본인 계좌만 |
| 명의 | monticker 운영자 1인 | 서비스 이용자 각자 |
| 저장 위치 | `.env` (worker) | DB `BrokerageAccount`(암호화) |
| KIS 환경변수 | `KIS_APP_KEY` / `KIS_APP_SECRET` | (프론트에서 사용자가 직접 입력, .env 없음) |
| Toss 환경변수 | `TOSS_PLATFORM_APP_KEY` / `TOSS_PLATFORM_APP_SECRET` | `TOSS_APP_KEY` / `TOSS_APP_SECRET`(코드 주석: "BYOK 전용, .env 금지") |

KIS는 우연히 두 용도가 같은 앱키 발급 절차를 공유한다(운영자 개인 계좌로 발급받은 키를 플랫폼 키로 재사용). Toss는 반드시 별도 이름(`TOSS_PLATFORM_*`)으로 `.env`에 넣는다 — `TOSS_APP_KEY`/`TOSS_APP_SECRET`는 사용자별 값이 코드 호출 시점에 전달되는 구조라 `.env`에 넣을 이름이 아니다.

---

## 1. KIS (한국투자증권) 앱키 발급

출처: [koreainvestment/open-trading-api](https://github.com/koreainvestment/open-trading-api) 공식 저장소 README — 이 세션에서 직접 확인.

### 1.1 사전 준비물

1. **한국투자증권 계좌** — 실전투자계좌(비대면 개설 가능, [한투 앱](https://securities.koreainvestment.com/main/customer/cusGuide/customerGuide.jsp)) 또는 모의투자계좌
2. **한국투자증권 HTS ID 등록**

> ⚠️ HTS ID에 `$`나 `@`가 포함돼 있으면 이후 ID 변경 시 Open API 사용에 제한이 생길 수 있다 — ID 변경 후 가입을 권장한다(공식 README 명시).

**실전투자계좌 권장**: 이 프로젝트의 `KisWebSocketClient`는 실시간 체결가(H0STCNT0) 기준으로 만들었고, 모의투자 WebSocket이 동일한 실시간 데이터를 주는지는 검증하지 않았다(ADR-030). 시세 수집이 목적이면 실전투자계좌를 쓴다.

### 1.2 신청 절차

1. [KIS Developers](https://apiportal.koreainvestment.com/) 접속 → 우상단 **API신청** 클릭
   (또는 한투 홈페이지 [서비스신청 > Open API > KIS Developers])
2. 공동인증서/금융인증서 로그인 → 휴대폰 본인인증
3. 유의사항(개인정보 수집·이용 동의서, 이용약관) 확인 및 동의 — 미동의 시 서비스 이용 불가
4. 서비스 신청 화면에서 API를 연결할 계좌 선택 (한 번에 최대 2개 계좌까지 가능) → 신청 버튼
5. 처음 로그인 때와 같은 방식으로 재인증
6. **신청 완료 즉시 그 화면에서 APP Key / APP Secret 발급** — 카카오톡 알림톡 또는 문자로 신청 완료 안내 + KIS Developers 홈페이지 임시 비밀번호 수신 (홈페이지 ID = HTS ID)

### 1.3 확인 및 관리

- 신청정보 화면의 신청현황 테이블에서 App Key/Secret 확인 — 화면에 노출되지 않아 클립보드 복사로만 가져올 수 있다
- **무료, 즉시 발급, 별도 심사 없음**
- **유효기간 1년** — 만료 30일 전부터 갱신 가능(갱신 시 키가 재발급된다는 점 주의), 해지는 언제든 가능하며 해지 즉시 기존 키 무효화
- 유출 의심 시 홈페이지에서 즉시 재발급

---

## 2. Toss증권 Open API 앱키 발급

Toss 개발자 포털(`openapi.tossinvest.com`)은 이 세션에서 자동 조회가 차단돼(403) **정확한 화면별 절차까지는 확인하지 못했다** — 아래는 [ADR-026](decisions/026-toss-brokerage-integration.md)에서 이미 확인한 REST/AsyncAPI 스펙 기준으로 확실한 부분만 정리한 것이다. 실제 신청은 포털에 직접 접속해 최신 안내를 따라야 한다.

### 2.1 확실한 것

- **인증 방식**: OAuth2 `client_credentials` — `client_id`/`client_secret`을 `POST /oauth2/token`에 제출해 액세스 토큰 발급
- **모의투자 서버가 없다** — 발급받는 순간부터 실서버/실계좌 기반이다(KIS와 다른 점)
- 시세 조회(`trade:kr`/`trade:us` 등)는 계정 종속이 아니라 앱 단위 인증이면 충분하다는 것을 AsyncAPI 스펙으로 확인했다(ADR-031) — 즉 운영자 개인 명의 앱 하나로 플랫폼 전체 시세를 커버할 수 있다

### 2.2 확인 필요 (포털에서 직접 진행)

1. 토스증권 실계좌 개설 여부 확인(브로커리지 계좌가 사전 준비물일 가능성이 높다 — KIS와 같은 패턴으로 추정, 미확인)
2. `openapi.tossinvest.com` 접속 → 개발자 등록/앱 신청 메뉴 확인
3. 앱 등록 후 `client_id`/`client_secret` 발급 확인 — 즉시 발급인지 심사가 있는지도 미확인

> 이 절차를 실제로 진행한 뒤, 확인된 정확한 단계로 이 섹션을 갱신해줄 것 — 지금은 스펙에서 역으로 추론 가능한 부분만 담았다.

---

## 3. 발급 후 프로젝트에 반영하기

`backend/worker`가 읽는 `.env`(또는 배포 환경변수)에 아래를 채운다:

```bash
# KIS 플랫폼 키
KIS_APP_KEY=발급받은-앱키
KIS_APP_SECRET=발급받은-시크릿

# Toss 플랫폼 키 — TOSS_APP_KEY/SECRET(BYOK 전용)와 다른 이름이다, 혼동 금지
TOSS_PLATFORM_APP_KEY=발급받은-client_id
TOSS_PLATFORM_APP_SECRET=발급받은-client_secret

# 두 프로바이더를 동시에 켠다 — 콤마 구분(ADR-031)
INGESTION_SOURCE=kis,toss
```

`backend/worker`를 재시작해야 반영된다(`kis.app-key`/`toss.platform.app-key` 모두 부팅 시 1회 읽는 `@Value` 설정).

## 4. 반영 확인 방법

1. **로그로 연결 확인**
   - KIS: `KIS WebSocket approval key issued`, `KIS WebSocket connected` 로그
   - Toss: `Toss platform access token issued`, `Toss WebSocket[US] connected` / `Toss WebSocket[KR] connected` 로그
2. **커버리지 로그**: `Subscribing to N symbols for real-time execution ticks (H0STCNT0)`(KIS), `Toss execution tick coverage — US:51 KR:100`(Toss, 실제 숫자는 DB 상태에 따라 다름) — 예상한 종목 수와 일치하는지 확인
3. **실제 시세 흐름 확인** (이전 세션에서 실제로 썼던 방법):
   ```bash
   docker exec monticker-redis redis-cli GET "stock:price:KOSPI:005930"
   ```
   `tradeTime`이 현재 시각에 가깝게 계속 갱신되면 실데이터가 흐르는 것이다. 장 마감 중에는 KIS/Toss 모두 새 체결이 없으므로 이 방법 대신 로그의 연결 성공 여부로만 판단한다.
4. **브라우저로 최종 확인**: `apps/web`에서 종목 상세 페이지를 열어두고 가격이 리로드 없이 바뀌는지 확인 — ADR-029에서 검증한 것과 동일한 방법.

## 참고

- [ADR-030: KIS 실시간체결가 연동](decisions/030-kis-realtime-tick-ingestion.md)
- [ADR-031: Toss 실시간체결가 연동](decisions/031-toss-realtime-tick-ingestion.md)
- [ADR-029: 시세 브로드캐스트 파이프라인 복구](decisions/029-price-broadcast-pipeline.md) — 위 3번 확인법의 근거
