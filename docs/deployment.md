# 프로덕션 배포 가이드

이 문서는 monticker를 프로덕션 환경에서 실행하기 위한 외부 서비스 등록 및 환경변수 설정 절차를 설명합니다.

## 1. 소셜 로그인 OAuth2 앱 등록

### 1-1. Google

1. [Google Cloud Console](https://console.cloud.google.com/apis/credentials) → 프로젝트 생성
2. **API 및 서비스 → 사용자 인증 정보 → OAuth 2.0 클라이언트 ID** 생성
   - 유형: 웹 애플리케이션
   - 승인된 리디렉션 URI:
     ```
     https://api.monticker.io/login/oauth2/code/google
     ```
3. 발급된 클라이언트 ID / 시크릿을 환경변수에 설정:
   ```env
   GOOGLE_CLIENT_ID=...
   GOOGLE_CLIENT_SECRET=...
   ```

### 1-2. Kakao

1. [Kakao Developers Console](https://developers.kakao.com/console/app) → 내 애플리케이션 → 앱 추가
2. **앱 설정 → 플랫폼 → Web** → 도메인 등록: `https://api.monticker.io`
3. **제품 설정 → 카카오 로그인 → 활성화 ON**
4. **Redirect URI** 등록:
   ```
   https://api.monticker.io/login/oauth2/code/kakao
   ```
5. **동의항목**: `profile_nickname`, `account_email` 필수 동의 설정
6. **앱 설정 → 앱 키 → REST API 키**를 CLIENT_ID로 사용:
   ```env
   KAKAO_CLIENT_ID=...
   KAKAO_CLIENT_SECRET=...  # 보안탭 → Client Secret 발급
   ```

### 1-3. Naver

1. [Naver Developers](https://developers.naver.com/apps) → Application 등록
2. **API 사용 설정**: 네아로(네이버 아이디로 로그인) 선택
3. **서비스 URL**: `https://monticker.io`
4. **Callback URL**:
   ```
   https://api.monticker.io/login/oauth2/code/naver
   ```
5. 발급된 값 설정:
   ```env
   NAVER_CLIENT_ID=...
   NAVER_CLIENT_SECRET=...
   ```

> **로컬 개발 시**: `SOCIAL_MOCK_ENABLED=true` → MockSocialLoginController 활성화 (실제 OAuth 없이 동작)

---

## 2. 토스페이먼츠 PG 연동

### 2-1. 앱 등록

1. [토스페이먼츠 개발자센터](https://developers.tosspayments.com) → 회원가입 → 내 상점 등록
2. **연동 정보 → 시크릿 키** 확인
   - 테스트 키: `test_sk_...` (테스트 결제, 실제 청구 없음)
   - 라이브 키: `live_sk_...` (실제 결제)

### 2-2. 환경변수

```env
TOSS_SECRET_KEY=live_sk_...
PG_MOCK_ENABLED=false
```

### 2-3. 결제 플로우

```
프론트 (토스 SDK)          백엔드                    토스 서버
    |                         |                          |
    |── 결제 위젯 표시 ───────→ |                          |
    |                         |                          |
    |← 결제 완료 (paymentKey)─|                          |
    |                         |                          |
    |── POST /api/subscription/payment/confirm ─────────→|
    |     {paymentKey, orderId, amount, planCode, userId} |
    |                         |── /v1/payments/confirm ─→|
    |                         |← 200 {status: "DONE"} ──|
    |                         |                          |
    |                         |── 구독 활성화 (DB 저장)   |
    |← {success: true} ───────|                          |
```

### 2-4. 웹훅 설정

토스페이먼츠 콘솔 → 웹훅 URL 등록:
```
https://api.monticker.io/api/subscription/payment/webhook
```

이벤트: `PAYMENT_STATUS_CHANGED`, `DEPOSIT_CALLBACK` (가상계좌 입금)

---

## 3. KIS Open API (한국투자증권) 연동

### 3-1. API 신청

1. [KIS Developers](https://apiportal.koreainvestment.com) → 회원가입 → 앱 신청
2. **국내주식 주문 API** (TTTC0802U, TTTC0801U) 이용 신청
3. 심사 완료 후 **앱 키 / 앱 시크릿** 발급

### 3-2. 계좌 설정

- **모의투자 서버**: `https://openapivts.koreainvestment.com:29443` (개발/테스트)
- **실거래 서버**: `https://openapi.koreainvestment.com:9443` (프로덕션)

### 3-3. 환경변수

```env
KIS_BASE_URL=https://openapi.koreainvestment.com:9443
BROKERAGE_MOCK_ENABLED=false
```

### 3-4. 토큰 흐름

KIS API는 계좌별 앱 키/시크릿으로 OAuth2 토큰을 발급받아 사용합니다.  
사용자가 `/api/brokerage/connect`를 호출 시 토큰이 발급되어 `brokerage_accounts` 테이블에 저장됩니다.  
토큰 만료(24시간) 시 재연동 필요.

---

## 4. 필수 환경변수 체크리스트

프로덕션 배포 전 아래 항목이 모두 설정되어 있는지 확인합니다.

| 환경변수 | 설명 | 필수 |
|----------|------|------|
| `DB_URL` | PostgreSQL 접속 URL | ✅ |
| `DB_USER` | DB 사용자 | ✅ |
| `DB_PASSWORD` | DB 비밀번호 | ✅ |
| `JWT_SECRET` | JWT 서명 키 (32바이트 이상) | ✅ |
| `APP_BASE_URL` | 서비스 기본 URL | ✅ |
| `ALLOWED_ORIGINS` | CORS 허용 도메인 | ✅ |
| `GOOGLE_CLIENT_ID` | Google OAuth2 | ✅ |
| `GOOGLE_CLIENT_SECRET` | Google OAuth2 | ✅ |
| `KAKAO_CLIENT_ID` | Kakao OAuth2 | ✅ |
| `KAKAO_CLIENT_SECRET` | Kakao OAuth2 | ✅ |
| `NAVER_CLIENT_ID` | Naver OAuth2 | ✅ |
| `NAVER_CLIENT_SECRET` | Naver OAuth2 | ✅ |
| `TOSS_SECRET_KEY` | 토스페이먼츠 시크릿 키 | ✅ |
| `KIS_BASE_URL` | KIS Open API 기본 URL | ✅ |
| `SOCIAL_MOCK_ENABLED` | `false` 필수 | ✅ |
| `PG_MOCK_ENABLED` | `false` 필수 | ✅ |
| `BROKERAGE_MOCK_ENABLED` | `false` 필수 | ✅ |
| `REDIS_HOST` / `REDIS_PORT` | Redis | ✅ |
| `KAFKA_BROKERS` | Kafka | ✅ |
| `MONGODB_URI` | MongoDB | ✅ |
| `ELASTICSEARCH_URI` | Elasticsearch | ✅ |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | 이메일 발송 | 선택 |
| `ANTHROPIC_API_KEY` | AI 분석 기능 | 선택 |

---

## 5. Spring Boot 프로파일 활성화

```bash
# Docker / 쿠버네티스
SPRING_PROFILES_ACTIVE=prod

# java 직접 실행
java -jar monticker-api.jar --spring.profiles.active=prod
```

`application-prod.yml`이 `application.yml` 위에 오버레이됩니다.

---

## 6. 보안 주의사항

- JWT_SECRET는 openssl 등으로 생성한 랜덤 문자열 사용:
  ```bash
  openssl rand -base64 48
  ```
- `*_MOCK_ENABLED` 환경변수는 절대 프로덕션에서 `true`로 설정 금지
- 토스페이먼츠 라이브 키 / KIS 앱 시크릿은 Secret Manager나 Vault로 관리 권장
- OAuth 리디렉션 URI는 각 콘솔에 등록된 URI와 정확히 일치해야 함
