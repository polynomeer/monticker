# monticker — 상용화까지 사람이 직접 해야 하는 일

> Read this when: 상용 제품화를 위해 "코드로 못 끝내는" 항목만 모아서 확인할 때.

이 문서는 [launch-plan.md](launch-plan.md)·[product.md](product.md)·[legal-review-brief.md](legal-review-brief.md)·[deployment.md](deployment.md)·[platform-api-keys.md](platform-api-keys.md)에 흩어져 있는 항목 중 **AI가 코드로 대신 끝낼 수 없는 것만** 추려 한곳에 모은 것이다. 각 항목이 왜 사람이 해야 하는지(계정 개설, 실명 인증, 법적 판단, 금전 계약 등)와 근거 문서 링크를 같이 적었다. 코드로 구현 가능한 나머지 작업은 [engineering-backlog.md](engineering-backlog.md) 참고.

이 문서 자체는 갱신 대상이다 — 항목이 실제로 완료되면 체크하고, 완료일과 결과를 한 줄로 남겨서 [launch-plan.md](launch-plan.md)의 해당 Phase 체크박스도 같이 갱신할 것.

---

## 1. 법무·컴플라이언스 — 가장 큰 병목

실제 변호사 자문이 나오기 전까지는 다른 어떤 코드 작업으로도 이 항목들을 대신할 수 없다.

- [ ] **자본시장법 — BYOK 증권사 연동이 투자중개업 인가 대상인지** — 질문지 준비 완료: [legal-review-brief.md §2-1](legal-review-brief.md#2-1-증권사-연동byok이-투자중개업-인가-대상인가)
- [ ] **유사투자자문업 신고 대상 여부 (Quant Lab/전략 마켓)** — [legal-review-brief.md §2-2](legal-review-brief.md#2-2-quant-lab--전략-마켓이-유사투자자문업-신고-대상인가)
- [ ] **전자금융거래법 — 결제·정산 구조에 자체 라이선스 필요 여부** — [legal-review-brief.md §2-3](legal-review-brief.md#2-3-전자금융거래법--결제정산-구조에-회사-자체-라이선스가-필요한가)
- [ ] **이용약관·개인정보처리방침 문구의 법적 충분성** (특히 면책·환불 조항) — [legal-review-brief.md §2-4](legal-review-brief.md#2-4-이용약관개인정보처리방침-문구의-법적-충분성)
- [ ] **개인정보 국외 이전(Anthropic API 전송) 고지 의무 여부** — [legal-review-brief.md §2-5](legal-review-brief.md#2-5-개인정보-국외-이전-고지)
- [ ] **통신판매업 신고 필요 여부** — [legal-review-brief.md §2-6](legal-review-brief.md#2-6-통신판매업-신고-필요-여부)
- [ ] **사업자 등록** (개인사업자 vs 법인 선택 — 지분 구조·투자 유치 계획에 따라 세무사·법무사 상담 필요) — [legal-review-brief.md §3](legal-review-brief.md#3-사업자-등록--일반-절차-정보-참고용-최종-선택은-세무사법무사-상담-권장)
- [ ] **Creator 수익 정산의 실제 세무 처리** (원천징수 등, [ADR-016](decisions/016-subscription-creator-revenue-sharing.md)) — 세무사 상담 필요

**자문이 나오면**: [legal-review-brief.md §4](legal-review-brief.md#4-자문-결과-반영-방법)에 적힌 절차대로 반영(질문 아래 결과 요약 추가 → `/terms`·`/privacy` 초안 배너 제거 → launch-plan.md 체크 → 구조 변경이 필요하면 새 ADR).

---

## 2. 외부 계정/키 발급 — 실명·사업자 인증이 필요해 AI가 대신 진행 불가

### 2-1. 실시간 시세 플랫폼 키 (ADR-030/031)
- [ ] **KIS 플랫폼 앱키** — 실전투자계좌 필요. 발급 절차 전체 확정: [platform-api-keys.md §1](platform-api-keys.md#1-kis-한국투자증권-앱키-발급) (무료·즉시 발급, 유효기간 1년)
- [ ] **Toss 플랫폼 앱키 + 허용 IP 등록** — 토스증권 실계좌 필요. 절차 확정: [platform-api-keys.md §2](platform-api-keys.md#2-toss증권-open-api-앱키-발급). **`backend/worker`를 배포할 서버의 고정 공인 IP를 먼저 확보**해야 함 — 동적 IP 환경이면 이 단계 자체가 막힌다.
- [ ] 두 키 발급 후 `.env`/배포 환경변수에 반영 ([platform-api-keys.md §3](platform-api-keys.md#3-발급-후-프로젝트에-반영하기)) — 이 반영 자체는 사람이 값을 넣는 것뿐이라 여기 포함했지만, 이후 라이브 검증은 AI가 이어서 할 수 있다.

### 2-2. 결제
- [ ] **토스페이먼츠 라이브 키 발급** — 실제 사업자 계정으로 [토스페이먼츠 개발자센터](https://developers.tosspayments.com) 가입 및 상점 등록 필요 ([deployment.md §2-1](deployment.md)). 사업자등록증(§1의 사업자 등록 완료 후) 전제.
- [ ] 토스페이먼츠 콘솔에 웹훅 URL 등록 (`https://api.monticker.io/api/subscription/payment/webhook`) — 실 도메인이 있어야 가능 ([deployment.md §2-4](deployment.md))

### 2-3. 소셜 로그인 OAuth2 앱 3종
- [ ] **Google Cloud Console**에서 OAuth 2.0 클라이언트 ID 발급 ([deployment.md §1-1](deployment.md))
- [ ] **Kakao Developers**에서 앱 등록 + 카카오 로그인 활성화 + Client Secret 발급 ([deployment.md §1-2](deployment.md))
- [ ] **Naver Developers**에서 네이버 아이디로 로그인 앱 등록 ([deployment.md §1-3](deployment.md))
- 셋 다 **실제 서비스 도메인**이 확정돼야 정확한 Redirect URI를 등록할 수 있음 — §3-1(도메인)이 선행돼야 함

---

## 3. 인프라·도메인 — 실제 계정/결제 수단 필요

- [ ] **도메인 구매 + DNS 설정** — 실제 도메인 소유가 필요해 이전 세션에서 이미 "이 세션에서 처리 불가"로 확인됨 ([launch-plan.md Phase 3](launch-plan.md))
- [ ] **TLS 인증서** — 도메인 확보 후 (Let's Encrypt 등으로 코드/자동화는 가능하지만, 도메인 자체는 사람이 사야 함)
- [ ] **실 클라우드 계정 개설** (AWS/GCP/등) + 결제 수단 등록
- [ ] **실 K8s 클러스터 프로비저닝** — 매니페스트(`infra/k8s/`)는 정적 검증까지 완료돼 있음, 실제 클러스터에 적용하는 단계만 남음
- [ ] **Secret Manager/Vault 실제 전환** — 템플릿은 준비됨([infra/k8s/base/external-secrets-example/](../infra/k8s/base/external-secrets-example/README.md)), 실제 클라우드 시크릿 백엔드 프로비저닝만 사람이 할 일

---

## 4. 실사용 검증 — 실제 계좌·전문 인력 필요

- [ ] **KIS/Toss 모의투자 계좌로 실 API E2E 최소 1회** — KIS는 모의투자 서버가 있어 실제 앱키만 있으면 가능하지만, **Toss는 모의투자 서버 자체가 없어 실거래로만 검증 가능**. 실거래 검증은 AI가 대신 실행할 수 없는 항목(금융 거래 실행 금지) — 사람이 직접 소액으로 실행하고 결과만 공유하면 이어서 코드 쪽 확인은 AI가 할 수 있다.
- [ ] **보안 침투 테스트(펜테스트)** — 외부 업체 계약 또는 자체 수행 인력 필요, 최소 1회 ([launch-plan.md Phase 6](launch-plan.md))

---

## 5. 진행 순서 요약

```
1. 사업자 등록 (§1) ── 통신판매업 신고 여부 확정 후 진행
2. 도메인 확보 (§3) ── OAuth 앱 3종 + 웹훅 등록의 전제
3. 법률 자문 5건 (§1) ── Closed beta/GA 게이트, 가장 오래 걸릴 가능성
4. KIS/Toss 플랫폼 키 (§2-1) ── 실시간 시세 라이브 검증의 전제, 나머지와 독립적으로 진행 가능
5. 토스페이먼츠 라이브 키 (§2-2) ── 사업자 등록 + 도메인 확보 후
6. 실 클라우드/K8s (§3) ── 도메인과 병행 가능
7. 실사용 검증·펜테스트 (§4) ── 위 항목이 어느 정도 갖춰진 뒤
```

법률 자문(§1)과 플랫폼 키 발급(§2-1)은 서로 의존하지 않으므로 **동시에 진행**할 수 있다 — 병목을 줄이려면 이 둘을 가장 먼저 시작할 것.
