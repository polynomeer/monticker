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

## Phase 2 — 보안 강화

- [ ] `security-review` 스킬 또는 외부 보안 검토로 전체 브랜치 1회 이상 점검.
- [ ] 의존성 취약점 스캔(Dependabot/Snyk 등)을 CI에 연결 — 현재 `.github/workflows/`(backend-ci/e2e-ci/mobile-ci/pr-review/web-ci)에 없음.
- [ ] JWT 시크릿 로테이션 절차 수립, refresh token 탈취 시나리오 대응 검토.
- [ ] Rate Limiting 재검토 — [architecture.md "Rate Limiting — 2-tier"](architecture.md#rate-limiting--2-tier)의 기존 설계가 실거래 트래픽 패턴에도 충분한지.
- [ ] 프로덕션 시크릿 관리를 `.env`에서 Secret Manager/Vault로 전환 — [docs/deployment.md §6](deployment.md)이 이미 권고하고 있으나 실제 적용 여부 확인.

---

## Phase 3 — 인프라/배포

[docs/deployment.md](deployment.md)가 외부 서비스 등록(OAuth, PG, KIS)과 환경변수 체크리스트를 이미 다룬다. 여기서는 그 다음 단계, 즉 "실제로 띄우고 운영하는" 부분만 다룬다.

- [ ] **K8s 매니페스트 실검증.** `infra/k8s/base/*.yaml` + `infra/k8s/overlays/{dev,prod}/`가 이미 존재하지만, 실제 클라우드 클러스터에 적용되어 검증된 적이 있는지 확인 필요 — 코드로는 있으나 실행 이력 미확인 상태.
- [ ] **CI/CD에 배포 스텝 추가.** 현재 `.github/workflows/*.yml` 5개 전부 빌드/테스트만 하고 `deploy`/`kubectl`/`helm` 스텝이 전혀 없다 — 배포는 전적으로 수동. 최소한 스테이징 자동배포부터 추가.
- [ ] 도메인/DNS/TLS 설정 — `deployment.md`의 `api.monticker.io` 등 도메인이 실제로 등록·연결되어 있는지 확인.
- [ ] DB 백업 정책 수립 + PITR 복구 절차 1회 이상 리허설.
- [ ] 모니터링 알림 채널 실연결 — Prometheus/Grafana는 `docker-compose.yml`에 있으나 온콜/Slack 등 실제 알림 라우팅 확인.
- [ ] 부하 테스트 1회 이상 — 특히 실시간 시세 파이프라인과 브로커 API rate limit 하에서의 동작.

---

## Phase 4 — 결제/정산 실사용 전환

[docs/settlement.md](settlement.md)의 4종 정산(페이퍼/전략마켓/구독/증권사) 설계를 Mock에서 Real로 전환하는 단계.

- [ ] `PG_MOCK_ENABLED=false` 전환 전 토스페이먼츠 라이브 키 발급 + 웹훅 실검증([deployment.md §2](deployment.md)).
- [ ] `BROKERAGE_MOCK_ENABLED=false` 전환은 **Phase 0(암호화·동시성·서킷브레이커) + Phase 1(법률 검토) 완료 후에만** — 순서를 건너뛰지 않는다.
- [ ] Creator 수익 정산([ADR-016](decisions/016-subscription-creator-revenue-sharing.md))의 실제 세무 처리(원천징수 등) 확인.

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
| 2 | 보안 점검 1회 이상 완료 | Phase 7의 Closed beta 진행 허용 |
| 3 | 배포 파이프라인 + 백업/모니터링 확인 | Phase 7의 모든 단계 진행 허용 |
| 6 | E2E + 펜테스트 완료 | Phase 7의 GA 진행 허용 |

이 요약표에서 어느 한 줄이라도 미완료면, 그 줄이 막는 다음 단계로 넘어가지 않는다.
