# monticker — 상용 출시 계획 (Launch Plan)

> Read this when: 상용 배포/출시 준비 진행 상황을 추적하거나, 다음에 뭘 해야 할지 확인할 때.

[ADR-023](decisions/023-commercialization-pivot.md)이 "**왜** MVP에서 상용 서비스로 전환하는가"를 기록한 결정문이라면, 이 문서는 "**무엇을, 어떤 순서로**" 실행하는가를 추적하는 살아있는 체크리스트다. 완료된 항목은 체크하고, 새로 발견되는 항목은 추가한다. PR에서 관련 작업을 마칠 때 이 문서도 함께 갱신한다.

## 이 문서를 읽는 법 — Phase는 게이트다

각 Phase는 순서대로 완료하는 목록이 아니라 **게이트**다. 특히:

- **Phase 0을 완료하지 않고 `BROKERAGE_MOCK_ENABLED=false`로 전환 금지.** 현금 동시성 버그·평문 크리덴셜 상태로 실계좌를 연결하면 기술 부채가 아니라 금전 사고가 된다.
- **Phase 1(법무)을 완료하지 않고 Phase 7(퍼블릭 출시) 진행 금지.** 특히 `/privacy`가 실제로 존재하는 페이지가 되기 전까지는 회원가입 자체가 법적으로 문제가 될 수 있다.

법률/컴플라이언스 항목(Phase 1)은 실제 법률 자문이 필요한 영역을 **가리키는 것**이지, 법률적 결론을 내리는 것이 아니다. "필요할 가능성이 높다"는 표현은 전문가 확인 전까지 확정으로 읽지 않는다.

---

## 관련 문서 지도 — 중복 대신 링크

이 문서는 아래 문서들이 이미 다루는 내용을 반복하지 않는다. 각 Phase에서 해당 문서로 연결한다.

| 문서 | 다루는 것 |
|---|---|
| [ADR-023](decisions/023-commercialization-pivot.md) | 상용화 전환 결정, BYOK 원칙, AI 가드레일 |
| [docs/product.md](product.md) — "상용화 로드맵" | 기능 단위 활성화 순서 (Quant Lab UI, Strategy Market, 실주문 등) |
| [docs/architecture.md](architecture.md) — "Brokerage Adapter" | BYOK 어댑터 설계, 서킷브레이커/암호화/동시성 요구사항 |
| [docs/deployment.md](deployment.md) | 외부 서비스(OAuth, 토스페이먼츠, KIS) 등록 절차, 프로덕션 환경변수 체크리스트 |
| [docs/settlement.md](settlement.md) | 정산 시스템 설계 (페이퍼/전략마켓/구독/증권사 4종), Mock→Real 전환 지점 |
| [docs/domain/quant-lab-positioning.md](domain/quant-lab-positioning.md) | Strategy Market이 유사투자자문업과 선을 긋는 제품 설계 근거 |
| [docs/domain/risk-management-trust.md](domain/risk-management-trust.md) | 리스크 한도의 제품적 근거 |

---

## Phase 0 — 선행 기술 부채 (실거래 연동 전 필수, 블로킹) — ✅ 완료 (2026-09-05)

[ADR-023](decisions/023-commercialization-pivot.md)에서 식별된 항목. 모의투자에서는 무해했지만 실제 브로커 계좌·실제 돈이 걸리면 사고로 직결된다.

- [x] **브로커 크리덴셜 암호화 저장.** `EncryptedStringConverter`(`common/security/EncryptedStringConverter.kt`, AES-256-GCM, Spring-managed JPA `AttributeConverter`)를 `BrokerageAccount.accessToken`에 적용. 키는 `app.security.credential-encryption-key`(env: `CREDENTIAL_ENCRYPTION_KEY`) — 프로덕션은 `application-prod.yml`에서 기본값 없이 필수 주입, 로컬 개발용 기본값과 다른 별도 키 사용 필수. 단위 테스트(`EncryptedStringConverterTest`, 5건)로 왕복 정확성·IV 랜덤성·평문 비노출을 검증했고, 실제 Spring 컨텍스트 기동으로 Hibernate 빈 컨테이너 연동도 확인했다(BeanCreationException 없음).
- [x] **현금 예약 동시성.** `OrderSagaOrchestrator.reserveCash`가 확인과 차감을 `UPDATE paper_accounts SET cash = cash - ? WHERE user_id = ? AND cash >= ?` 하나의 원자적 문장으로 통합 — 과거의 "SELECT로 확인 → 별도 UPDATE로 차감" TOCTOU 레이스를 제거했다. 실제 Postgres(Testcontainers)에 10개 스레드를 동시 투입해 잔고가 절대 마이너스로 떨어지지 않음을 증명하는 통합 테스트(`CashReservationConcurrencyIntegrationTest`) 추가.
- [x] **서킷브레이커 공백.** `CircuitBreakerConfiguration`에 `"kis"` 브레이커 등록, `KisBrokerageClient`의 5개 메서드(토큰 발급/주문/조회/정산/잔고) 전부 `cb.executeCallable { ... }`로 래핑 — `CallNotPermittedException` 시 기존 REJECTED/빈 값 폴백과 동일한 안전한 기본값 반환. 신규 `TossBrokerageClient`는 이 파일의 패턴(`"kis"` → `"toss"`)을 그대로 따르면 된다.

---

## Phase 1 — 법무·컴플라이언스 (전문가 검토 필요)

> 아래 항목은 "검토가 필요하다"는 안내이며, 법률적 결론이 아니다. 실제 진행 전 변호사/법무 자문을 거친다.
> Claude가 자문 없이 완료할 수 없는 항목(사업자 등록, 4개 법률 검토)은 [docs/legal-review-brief.md](legal-review-brief.md)에
> 자문용 브리핑(사실관계 + 질문지)만 준비해 두었다 — **체크박스는 실제 자문 결과가 반영된 뒤에만 체크한다.**

- [ ] **사업자 등록.** 개인사업자/법인 여부 결정 → 사업자 등록. 구독 판매·전략 마켓 운영 시 통신판매업 신고 필요 가능성 검토. → 일반 절차 안내는 [legal-review-brief.md §3](legal-review-brief.md#3-사업자-등록--일반-절차-정보-참고용-최종-선택은-세무사법무사-상담-권장), 최종 형태 선택은 세무사·법무사 상담 필요.
- [x] **이용약관 작성 및 게시** (`/terms`) — [apps/web/src/app/terms/page.tsx](../apps/web/src/app/terms/page.tsx) 초안 게시 완료(2026-09-05). 12개 조 구성, 확정 안 된 항목은 주황색 표시. `CookieBanner`에서 "이용약관" 링크 추가.
- [x] **개인정보처리방침 작성 및 게시** (`/privacy`) — 완료(2026-09-05, 이전 커밋). 깨진 링크였던 문제 해결됨.
- [ ] **자본시장법 검토 — BYOK 모델의 인가 대상 여부.** → 자문 질문지 준비 완료: [legal-review-brief.md §2-1](legal-review-brief.md#2-1-증권사-연동byok이-투자중개업-인가-대상인가). 실제 자문 대기.
- [ ] **유사투자자문업 신고 대상 여부.** → 자문 질문지 준비 완료: [legal-review-brief.md §2-2](legal-review-brief.md#2-2-quant-lab--전략-마켓이-유사투자자문업-신고-대상인가). 실제 자문 대기.
- [ ] **전자금융거래법.** → 자문 질문지 준비 완료: [legal-review-brief.md §2-3](legal-review-brief.md#2-3-전자금융거래법--결제정산-구조에-회사-자체-라이선스가-필요한가). 실제 자문 대기.
- [ ] **개인정보 국외 이전 고지.** `/privacy`에 Anthropic 위탁 사실은 이미 명시해 두었으나, 법적으로 별도 고지 형식이 필요한지는 미확인 → [legal-review-brief.md §2-5](legal-review-brief.md#2-5-개인정보-국외-이전-고지). 실제 자문 대기.
- [ ] **투자자문 아님 고지 문구의 법적 충분성 검토.** `/terms` 제5·6·11조, `/privacy`에 초안 문구는 반영했으나 법적 충분성은 미확인 → [legal-review-brief.md §2-4](legal-review-brief.md#2-4-이용약관개인정보처리방침-문구의-법적-충분성). 실제 자문 대기.
- [ ] **통신판매업 신고 필요 여부.** → [legal-review-brief.md §2-6](legal-review-brief.md#2-6-통신판매업-신고-필요-여부). 실제 자문 대기.

---

## Phase 2 — 보안 강화 — ✅ 완료 (2026-09-05)

- [x] `security-review` 스킬로 전체 브랜치 점검 (origin/main...HEAD, 14커밋/36파일) — 고신뢰 취약점 0건. 하드닝 성격 커밋(암호화·동시성·서킷브레이커)이라 새 공격 표면 없음.
- [x] 의존성 취약점 스캔을 CI에 연결 — [.github/dependabot.yml](../.github/dependabot.yml) 신설(npm/gradle 4개 모듈/gomod/docker/github-actions 전체 커버), `web-ci.yml`에 `pnpm audit --audit-level=high` 추가, `backend-ci.yml`에 `gradle/actions/dependency-submission` 추가(Gradle은 Dependency Graph가 있어야 Dependabot alert가 켜짐).
- [x] **JWT/refresh token 재검토 중 실제 취약점 2건 발견 및 수정**:
  - `refresh_tokens.token` 컬럼이 평문 JWT를 저장하고 있었음(비밀번호는 이미 해시 저장 중인데 여기만 예외) → SHA-256 해시만 저장하도록 전환(`V31__hash_refresh_tokens.sql`, `AuthService.hashToken`). DB 유출 시 저장값만으로는 세션을 재사용할 수 없다.
  - **`/api/auth/logout` 엔드포인트 자체가 없었음** — 비밀번호 재설정/계정삭제 시에만 전체 세션이 폐기되고, 사용자가 기기 하나만 로그아웃할 방법이 없었다 → 해당 refresh token 하나만 폐기하는 `/api/auth/logout` 추가.
  - 시크릿 로테이션 절차: `JWT_SECRET` 교체는 즉시 모든 세션을 무효화한다(HMAC 서명 불일치) — 개별 세션 취소가 아니라 "전체 강제 로그아웃" 수단으로만 사용. 개별 세션 취소는 위 `/logout` 또는 `refresh_tokens` 행 삭제로 처리.
- [x] **Rate Limiting 재검토 중 실제 gap 2건 발견 및 수정**:
  - `/api/brokerage/connect`, `/api/brokerage/orders`(실주문)에 `@RateLimited`가 전혀 없었음 — paper trading(`matching`/`paper` 컨트롤러)은 이미 되어 있었는데 실브로커 쪽만 빠져 있었다. 추가하지 않으면 사용자의 KIS/Toss API 키가 브로커 쪽 rate limit에 걸려 차단될 수 있다 → `connect`(10회/시간), `orders`(30회/분, matching과 동일) 추가.
  - `/api/auth/login`에 브루트포스 방어가 전혀 없었음(주석에 "인증 엔드포인트는 IP 기반 RateLimitFilter로 처리"라고 되어 있었지만 IP 기반만으로는 여러 IP에 분산된 크리덴셜 스터핑을 못 막는다) → 이메일 단위 실패 카운터(5회/15분, Redis) 추가 — 성공 시 즉시 리셋되므로 정상 사용자는 체감 못 함.
- [x] 프로덕션 시크릿 관리 — **아직 실제 전환은 안 됨** (실제 클라우드 계정·시크릿 백엔드 프로비저닝 필요, Claude가 대신할 수 없는 영역). 대신 실제 전환에 쓸 template을 준비: [infra/k8s/base/external-secrets-example/](../infra/k8s/base/external-secrets-example/README.md)(External Secrets Operator + AWS Secrets Manager 예시, Vault/GCP로 교체 가능). 점검 중 발견: `infra/k8s/base/secret.yaml`에 `JWT_SECRET`/`TOSS_SECRET_KEY`/`CREDENTIAL_ENCRYPTION_KEY`가 누락되어 있었음(prod 프로파일에 기본값이 없어 이 상태로 배포하면 부팅 자체가 실패) — placeholder로 추가.

세부 내역은 각 커밋 메시지 참고. 실제 사고 대응 시나리오(시크릿 유출 시 로테이션 순서 등)는 아직 별도 런북으로 정리되지 않음 — Phase 3(인프라) 이후 필요시 추가.

---

## Phase 3 — 인프라/배포 — 부분 완료 (2026-09-06)

[docs/deployment.md](deployment.md)가 외부 서비스 등록(OAuth, PG, KIS)과 환경변수 체크리스트를 이미 다룬다. 여기서는 그 다음 단계, 즉 "실제로 띄우고 운영하는" 부분만 다룬다. 실제 클라우드 계정·도메인이 필요한 항목은 이 세션에서 대신 처리할 수 없어 미완료로 남았다 — 그 외에는 실제로 실행/검증했다.

- [x] **K8s 매니페스트 검증 (정적 검증만 — 실 클러스터 없음).** `kubectl kustomize infra/k8s/overlays/{dev,prod}`로 렌더링해 에러 없이 24개 리소스가 나오는 것 확인, dev/prod 오버레이가 실제로 다르게 패치되는지(replica 수, 이미지 태그, MSA URL) diff로 확인. `kubectl apply --dry-run`은 API 서버 연결이 필요해서(kind/minikube 미설치) 여기까지만 — 실 클러스터 적용 검증은 여전히 미확인.
- [x] **DB 백업/복구 — 실제로 리허설함.** [infra/db/](../infra/db/README.md)에 `backup.sh`/`restore.sh` 추가. 로컬 dev DB에 실제로 백업→카나리아 행 삽입→복구를 실행해 정확히 백업 시점 상태로 돌아오는 것을 확인(README.md에 수치 기록). PITR(WAL 아카이빙)은 실 프로덕션 Postgres가 있어야 리허설 가능 — 미완료로 남김.
- [x] **모니터링 알림 채널 — 로컬에서 엔드투엔드로 실제 연결 확인.** 부수적으로 두 가지를 새로 발견해 고쳤다:
  - `resilience4j-micrometer` 의존성이 없어서 서킷브레이커 상태가 Prometheus에 전혀 노출되지 않고 있었음 → 추가 후 `resilience4j_circuitbreaker_state` 게이지 노출 확인.
  - **`/actuator/prometheus`가 SecurityConfig에서 `denyAll()`이었음 — Prometheus 자신의 스크레이프 요청도 401로 막혀 모니터링 전체가 애초에 동작 불능이었다.** Ingress가 `/actuator/**`를 라우팅하지 않아(공인 인터넷에서 원천 차단) 실제 보안 경계는 네트워크 토폴로지이므로 `permitAll()`로 변경.
  - [infra/monitoring/alert-rules.yml](../infra/monitoring/alert-rules.yml) 신설(ServiceDown, HighHttpErrorRate, CircuitBreakerOpen, HighJvmHeapUsage, HikariPoolNearExhaustion — `promtool check rules`로 검증), [infra/monitoring/alertmanager.yml](../infra/monitoring/alertmanager.yml) 신설(`amtool check-config`로 검증), `docker-compose.yml`에 alertmanager 서비스 추가.
  - **실제로 컨테이너를 띄워서 확인**: 서비스 다운 상황을 만들어 ServiceDown 알림이 pending → firing으로 전이하고 Alertmanager가 실제로 수신하는 것까지 API 응답으로 확인. 실제 채널(Slack 등) 연결은 사용자의 워크스페이스가 필요해 `alertmanager.yml`에 예시 설정만 남겨둠 — 미완료.
- [x] **부하 테스트 — 실제로 실행, 진짜 버그 2건 발견·수정.** [scripts/load-test/k6-smoke.js](../scripts/load-test/k6-smoke.js)(k6)로 로컬 스택에 실제 부하를 가함. 스크리너 조회·로그인 브루트포스·모의투자 주문 폭주 세 시나리오 모두 rate limit이 설계대로 걸리는 것을 확인하는 과정에서:
  - **`/error`가 SecurityConfig 인가 목록에 없어서, `RateLimitFilter.sendError(429)`가 내부적으로 `/error`로 재디스패치될 때 `anyRequest().authenticated()`에 걸려 429가 401로 둔갑하고 있었다.** 모든 rate-limit 응답이 실제 이유(429) 대신 "인증 안 됨"만 보여주고 있었던 것 — `/error`를 permitAll로 추가해 수정.
  - **`PaperTradingService.getCurrentPrice`/`OrderSagaOrchestrator.getCurrentPrice` 둘 다 최근 캔들이 없는 종목에 주문을 넣으면 `EmptyResultDataAccessException`이 새어나가 안내 메시지 없는 500을 반환했다.** `queryForObject`는 0건일 때 null이 아니라 예외를 던지므로 의도했던 `?: throw IllegalStateException(...)` 처리가 아예 실행되지 않고 있었음 — `query(...).firstOrNull()`로 교체해 두 곳 다 수정, 회귀 테스트 추가.
- [x] **CI/CD 배포 스텝 (이미지 빌드/푸시까지).** [.github/workflows/deploy-images.yml](../.github/workflows/deploy-images.yml) 신설 — main 머지 시 backend 4개 서비스 + market-gateway + web 이미지를 빌드해 GHCR(ghcr.io)에 푸시(별도 클라우드 계정 불필요, GITHUB_TOKEN만 사용). `apps/web/Dockerfile`은 원래 빌드 자체가 안 됐다(`package.json`은 `apps/web/`에만, `pnpm-lock.yaml`/`.npmrc`는 저장소 루트에만 있어 어느 빌드 컨텍스트를 줘도 한쪽이 빠짐) — pnpm 워크스페이스 인식 멀티스테이지 COPY로 재작성해 고침(`.dockerignore` 신설로 빌드 컨텍스트도 2GB+→정상 크기). 로컬에서 6개 서비스 중 market-gateway·web 둘 다 실제 `docker build` 성공 + web은 컨테이너를 띄워 `/`, `/screener`, `/privacy`, `/terms`, 정적 JS 에셋까지 200 응답 확인. 나머지 backend 4개는 각자 이미 CI(`backend-ci.yml`)에서 매 PR마다 실제로 빌드되므로 Dockerfile 자체 검증은 생략. **실제 K8s 클러스터로의 배포 스텝은 여전히 없음**(kubeconfig/클러스터 필요 — 이미지가 GHCR에 올라가는 것까지만, 그 이미지를 클러스터에 적용하는 단계는 미완료).
- [ ] 도메인/DNS/TLS 설정 — 실제 도메인 소유·클라우드 DNS가 필요해 이 세션에서 처리 불가.

---

## Phase 4 — 결제/정산 실사용 전환 — 부분 완료 (2026-09-06)

[docs/settlement.md](settlement.md)의 4종 정산(페이퍼/전략마켓/구독/증권사) 설계를 Mock에서 Real로 전환하는 단계.

- [x] **토스페이먼츠 웹훅 실검증 — 코드를 실제로 파보니 진짜 결제 흐름 자체가 깨져 있었다.** `PG_MOCK_ENABLED=false`로 직접 부팅해서 재현·수정한 것들:
  - **`TossPgClient`가 아예 부팅이 안 됐다** — `@Value("${app.toss.secret-key}")`가 실제 설정 경로(`app.pg.toss.secret-key`)와 다른 이름을 참조하고 있어 `PlaceholderResolutionException`으로 컨텍스트 시작 자체가 실패했다. 즉 지금까지 `PG_MOCK_ENABLED=false`로는 애초에 한 번도 뜬 적이 없었을 가능성이 높다.
  - **`/api/subscription/payment/webhook`이 인증 필수였다** — 토스 서버가 사용자 JWT를 들고 올 수 없으니 실제 웹훅은 전부 401로 막혔을 것. `permitAll`로 변경하고, 인증은 이 엔드포인트 자체가 PG에 재조회해서 하도록 함.
  - **웹훅 바디를 그대로 신뢰하고 있었다** — 토스 개발자센터 문서 확인 결과 일반 결제상태 웹훅(PAYMENT_STATUS_CHANGED 등)에는 서명이 없다(서명은 payout.changed/seller.changed 전용). `PgClient.getPaymentStatus()`를 추가해 웹훅을 "트리거"로만 쓰고 PG 재조회 값을 권위 있는 상태로 취급하도록 변경 — 실제로 가짜 시크릿 키로 부팅해 진짜 토스 API가 401을 돌려주는 것까지 확인.
  - **`PaymentWebhookController.confirm()`이 결제는 됐는데 구독은 활성화 안 되는 버그가 있었다** — 토스 confirm으로 결제를 이미 성공시킨 뒤 `subscriptionService.subscribe()`를 호출했는데, 이 메서드가 내부적으로 `pgClient.requestPayment()`를 또 호출한다. `TossPgClient.requestPayment()`는 웹훅 플로우를 쓰라는 스텁이라 항상 실패를 반환하므로, **고객은 실제로 결제됐는데 구독은 활성화되지 않고 PaymentRecord만 FAILED로 남는** 상태였다. `subscriptionService.activateConfirmedSubscription()`을 신설해 이미 확정된 결제를 재시도 없이 바로 활성화하도록 분리.
  - **`confirm()`이 broken object-level authorization이었다** — `userId`를 요청 바디에서 그대로 받았다. 로그인한 사용자가 바디에 임의의 `userId`를 넣으면 남의 계정에 구독을 활성화시킬 수 있었다. `SubscriptionController`의 다른 엔드포인트와 동일하게 JWT에서만 추출하도록 수정.
  - `/api/subscription/payment/confirm`에 `IdempotencyFilter` 적용 추가 — 네트워크 재시도로 confirm이 중복 호출되는 상황 방지.
  - 회귀 테스트 3건 추가, 전체 스위트 378/378.
- [ ] **토스페이먼츠 라이브 키 발급** — 실제 사업자·상점 계정이 필요해 이 세션에서 처리 불가([deployment.md §2](deployment.md)).
- [x] **실제 정기결제(자동 갱신) — 백엔드+프론트엔드 구현 완료.** 토스페이먼츠 정기결제는 confirm 플로우와 무관한 별도 빌링키 API라는 걸 [공식 문서](https://docs.tosspayments.com/guides/v2/billing/integration)로 확인 후 구현:
  - `user_billing_keys` 테이블 신설(V32) — `billing_key`는 `EncryptedStringConverter`로 AES-256-GCM 암호화 저장(브로커 API 키와 동일한 민감도로 취급).
  - `PgClient`에 `issueBillingKey()`(authKey↔billingKey 교환, `POST /v1/billing/authorizations/issue`)와 `chargeBilling()`(`POST /v1/billing/{billingKey}`) 추가 — Toss/Mock 양쪽 구현.
  - `BillingController` 신설(`/api/subscription/billing/{customer-key,register,GET,DELETE}`) — mock/real 양쪽 모드에서 동작, JWT에서만 userId 추출(바디 신뢰 안 함).
  - `SubscriptionService.renewSubscription()`을 저장된 빌링키로 `chargeBilling()`을 호출하도록 수정 — 빌링키가 없으면 결제 실패로 취급해 기존 3회 실패 다운그레이드 로직을 그대로 태움.
  - 실제로 부팅해서 회원가입→customer-key 발급→카드 등록(mock)→DB 조회로 암호화 저장 확인→해지까지 curl로 검증. 검증 중 `deregister()`가 `@Transactional` 없이 `deleteByUserId`를 호출해 500이 나는 버그를 발견·수정("파생 delete 쿼리는 `deleteById()`와 달리 리포지토리 프록시가 자체 트랜잭션을 안 열어준다").
  - **프론트엔드(`apps/web`) 위젯 연동 완료.** `@tosspayments/tosspayments-sdk`(공식 V2 JS SDK) 도입, `next.config.ts` CSP에 `js.tosspayments.com`(script) / `*.tosspayments.com`(iframe, connect) 허용 추가. `/subscription` 페이지에 "정기결제 카드" 섹션 신설 — 등록 시 `getOrCreateCustomerKey()` → `loadTossPayments(clientKey).payment({customerKey}).requestBillingAuth({method:"CARD", successUrl, failUrl})`로 토스 호스팅 카드 등록 위젯을 띄우고, `/subscription/billing/callback` 페이지가 성공(`authKey`/`customerKey`) · 실패(`code`/`message`) 리다이렉트를 구분해 처리한다. 브라우저에서 회원가입 → 위젯 오픈(CSP 위반 없음 확인) → mock 콜백으로 등록/해지까지 실제 렌더링으로 검증 완료. 토스 호스팅 카드입력 iframe 자체(PCI 격리 영역)는 이 세션의 브라우저 자동화 도구가 중첩 크로스오리진 iframe에 합성 입력을 전달하지 못해 직접 타이핑 검증은 못 했다 — 우리 코드가 책임지는 경계(SDK 호출, CSP, 콜백 처리)까지는 전부 라이브로 확인됨.
  - **검증 중 무관한 기존 버그 발견·수정**: `SubscriptionController.PlanResponse.features`가 엔티티의 `jsonb` 컬럼(실제로는 JSON 배열 문자열)을 파싱 없이 그대로 `String`으로 내려보내고 있어, `GET /api/subscription/plans` 응답이 `"features": "[\"a\", \"b\"]"` 형태의 문자열이었다. 프론트 `PlanCard`는 `plan.features.map(...)`으로 배열을 기대하므로 `/subscription` 페이지 전체가 `TypeError: ...map is not a function`으로 죽어 있었다(이 정기결제 카드 UI 검증 전부터 있던 버그, 정기결제 기능과 무관). `jacksonObjectMapper().readValue<List<String>>(features)`로 파싱해 `PlanResponse.features: List<String>`으로 수정.
- [ ] `BROKERAGE_MOCK_ENABLED=false` 전환은 **Phase 0(완료) + Phase 1(법률 검토, 아직 미완료) 완료 후에만** — 순서를 건너뛰지 않는다.
- [ ] Creator 수익 정산([ADR-016](decisions/016-subscription-creator-revenue-sharing.md))의 실제 세무 처리(원천징수 등) — 세무사 상담 필요, Claude가 대신할 수 없는 영역.

---

## Phase 5 — 기능 활성화 로드맵

기능 단위(Quant Lab UI, Strategy Market, 실주문, 리밸런싱 실행, 조건주문, AI 자동매매)의 우선순위와 각 기능별 비고는 [docs/product.md "상용화 로드맵"](product.md) 표에 이미 정리되어 있다 — 여기서는 중복하지 않는다.

---

## Phase 6 — QA / E2E

- [ ] 기존 통합테스트 스위트 그린 유지 확인 (백엔드/프론트 CI 전체).
- [ ] KIS/Toss **모의투자(paper) 계좌**로 실 API E2E 최소 1회 실행 — Mock이 아닌 실제 외부 API 응답 형태 검증.
- [ ] 보안 침투 테스트(펜테스트) — 외부 업체 또는 자체 수행, 최소 1회.

---

## Phase 7 — 단계적 출시

```
Private beta
  → 내부 인원만, BROKERAGE_MOCK_ENABLED=true 유지 (실브로커 연결 OFF)
  → 목적: 페이퍼트레이딩/Quant Lab UI/Strategy Market 안정성 확인

Closed beta
  → Phase 0~2 완료 후, 화이트리스트 사용자만
  → 실브로커 연동 ON, 초기 거래 한도(예: 1일 주문 건수/금액 상한) 강제
  → 목적: 실계좌 연동 경로의 실사용 검증, 사고 시 영향 범위 최소화

General Availability
  → Phase 1(법무) 전부 완료, Closed beta 기간 중 금전 사고 0건 확인 후
```

---

## 최종 Go/No-Go 게이트 요약

| Phase | 게이트 조건 | 다음 단계 진행 가능 조건 |
|---|---|---|
| 0 | ✅ 암호화·동시성·서킷브레이커 3항목 완료 (2026-09-05) | Phase 4의 `BROKERAGE_MOCK_ENABLED=false` 전환 허용 |
| 1 | 이용약관·개인정보처리방침 실제 게시 + 법률 자문 완료 | Phase 7의 Closed/GA 진행 허용 |
| 2 | ✅ 보안 강화 완료 (2026-09-05) — 시크릿 관리 전환만 실제 클라우드 프로비저닝 대기 | Phase 7의 Closed beta 진행 허용 |
| 3 | 🟡 부분 완료 (2026-09-06) — 백업/모니터링/부하테스트/이미지 빌드·푸시 실증 완료, 도메인·실클러스터 배포는 미완료 | Phase 7의 모든 단계 진행 허용 |
| 4 | 🟡 부분 완료 (2026-09-06) — 웹훅/결제 버그 6건 + 정기결제 백엔드·프론트엔드 위젯 연동 완료, 라이브 키 발급·세무 처리는 미완료 | 실제 유료 결제·구독 오픈 허용 (라이브 키 발급 전까지는 mock 유지) |
| 6 | E2E + 펜테스트 완료 | Phase 7의 GA 진행 허용 |

이 요약표에서 어느 한 줄이라도 미완료면, 그 줄이 막는 다음 단계로 넘어가지 않는다.
