# iOS 앱 디자인 적용 계획

> 읽을 때: iOS 디자인 시안(디자인 캔버스 "monticker iOS 앱 시안", 다크·라이트 페이지와 "추천안 · B + 상태 바")을 `apps/mobile` 코드에 옮길 때.
> 결정은 [ADR-113](decisions/113-mobile-shell-three-tabs-status-bar-themes.md).

지금 앱(`apps/mobile`, Expo SDK 57)은 화면 6개(스크리너 홈·관심종목·알림·내 계좌·종목 상세·로그인)와 하드코딩한 남색 팔레트뿐이다.
시안은 화면 17종 × 다크·라이트다. **기본 테마는 드라큘라 다크**이고, 라이트와 "시스템 따라가기"는 설정에서 고른다.

## 1. 시안 요약

| 영역 | 시안 |
|---|---|
| 내비게이션 | 하단 떠 있는 탭 3개(홈 · 관심종목 · 내 계좌) + 오른쪽에 떨어진 검색 버튼. 검색 화면에서는 탭 바가 홈 버튼 + 검색 입력창으로 바뀐다 |
| 상태 바 | 탭 바 위 알약: 모드 배지(모의 노랑 / 실전 주황) + 진행 중인 것(실행 중 전략·미체결). 모의는 진행 중인 것이 있을 때만, 실전은 늘. 내 계좌·종목 상세·주문에서는 숨김. 누르면 내 계좌 |
| 홈 | 큰 제목 "홈" + "관심종목 N개 · 전략 N개에서 새 소식 N건", 시장 요약 줄(장 상태·KOSPI·KOSDAQ·USD), "내 종목 · 새 소식" 카드(이벤트 + 스파크라인, 전략 신호, 공시, 리스크 경고) |
| 관심종목 | 그룹 칩, 요약 타일 3개(상승/하락·오늘 이벤트·알림 켜짐), 목록(이벤트 배지·거래량 배수·스파크라인·등락률 알약) |
| 검색 | 범위 칩(전체·종목·이벤트·전략·뉴스), 최근 검색어, 최근 본 종목, 많이 찾는 종목(거래대금 상위). 결과 화면은 범위별 개수와 묶음 |
| 종목 상세 | 시세·기간 칩(1분~1년)·MA20 차트, "왜 움직였나"(이벤트·공시), 하단 고정 보유 요약 + 매도·매수 버튼(상승·하락 색) |
| 주문 시트 | 매수/매도, 지정가, 수량(최대 수량·10/25/50%/최대), 주문 전 리스크 체크 3줄, 감정 태그, 예상 금액. 실전은 주황 고지 + **길게 눌러 주문** |
| 내 계좌 | 모의 \| 실전 분할 버튼, 총자산 + 현금·예약금·평가액 막대, 보유 \| 주문 \| 전략 탭, 최근 돈의 흐름 + 오늘의 리플레이. 실전은 증권사 이름 + 잔고 동기화 상태 |
| 전략 탭 | 포워드 테스트 카드(누적 수익·MDD·일치율·오늘 신호), 백테스트 완료·초안 카드, 전략 마켓 링크, 성과 고지 |
| 설정 | 프로필, 계정(모의 계좌·증권사 연동·구독), 알림·투자(채널·방해 금지·주간 리포트·리스크 한도), 화면(화면 모드·상승·하락 색), 보안(Face ID 잠금·동의 내역), 로그아웃 |
| 상승·하락 색 | 미리보기 + 화면 모드에 맞춤(기본: 다크 초록 상승, 라이트 빨강 상승) · 빨강 상승·파랑 하락 · 초록 상승·빨강 하락. 경고 빨강은 안 바뀐다 |
| 로그인·온보딩 | 소셜 버튼 + 이메일 로그인, 온보딩 3장(이벤트·모의투자·퀀트랩) |
| 로고·아이콘 | 이미 반영(#242) |

## 2. 시안과 다르게 가는 것

| 시안 | 코드 | 이유 |
|---|---|---|
| 공시 아이콘이 주황 | **청록** | 주황은 실전(실제 돈) 전용([ADR-112](decisions/112-web-shell-v2-search-rail-status-themes.md) 색 역할, 웹과 같은 규칙) |
| Apple·카카오·네이버·Google 로그인 버튼 | **이메일 로그인만**, 소셜은 서버 준비 뒤 | 네이티브 경로(`/api/auth/native/*`, ADR-109)는 이메일·비밀번호만 받는다. Apple 제공자는 서버에 없다 |
| 실전 "잔고 동기화 · 브로커 잔고와 일치" | **마지막 조회 시각과 연동 상태만** | 저장된 잔고와 증권사 잔고를 대조하는 API가 없다. 실전 잔고는 매번 증권사에서 바로 읽는다 |
| 프로필의 닉네임·이메일 | `GET /api/users/me`(응답 전에는 로그인 이메일) + 구독 플랜 | 처음엔 API가 없어 로그인 이메일만 보였다 |
| 주문 시트 "수수료·세금 별도" 예상 | 금액만, 고지 문구 유지 | 사전 수수료 추정 API가 없다 |
| "내 계좌"에 파일·손 입력 계좌 없음 | **실전 쪽 아래 "다른 증권 계좌" 줄**로 기존 통합 보유 화면(`accounts.tsx`) 유지 | ADR-106/110 통합 보유 기능을 지우지 않는다. 연동 잔고와 합치지 않는다(ADR-115) |

## 3. 단계 (단계마다 PR 하나)

API는 모두 지금 있는 것을 쓴다(아래 표). 백엔드 변경은 이 계획에 없다. 빈 곳은 §4에 따로 모았다.

### M1. 테마와 공통 부품

- `src/theme/` — 팔레트 2벌(드라큘라 다크 기본, 라이트), 색 역할(`up`·`down`·`live`·`caution`·`disclosure`·`danger`), 글자 크기(34 큰 제목 · 20 · 17 · 15 · 13 · 12).
- `ThemeProvider` — 화면 모드 `dark`(기본) \| `light` \| `system`, 상승·하락 색 `auto`(기본) \| `redUp` \| `greenUp`. 기기에 저장(`@react-native-async-storage/async-storage`).
  `system`일 때만 `useColorScheme()`을 따른다. 상태 표시줄 글자색도 따라 바뀐다.
- 공통 부품: `Screen`(큰 제목·안전 영역·하단 탭 여백), `Card`, `GroupedList`/`ListRow`(iOS 설정형 목록), `Chip`, `Segmented`, `Badge`, `ChangePill`, `Sparkline`, `GlassButton`(블러 원형 버튼), `Icon`(시안의 선 아이콘 세트).
- 새 의존성(모두 Expo SDK 57 고정 버전): `react-native-svg`(아이콘·스파크라인·차트), `expo-blur`(유리 탭 바), `expo-haptics`, `@react-native-async-storage/async-storage`.
  웹 렌더링 확인용 개발 의존성 `react-native-web`.
- `packages/types`에 모바일이 쓸 응답 타입을 더한다(관심종목·모의·지갑·리스크·시세·알림 설정). 지금은 웹 파일 안에만 있다.
- 기존 6개 화면의 색을 토큰으로 바꾼다(모양은 M2~M7에서).

### M2. 셸 — 탭 3개 + 검색, 상태 바, 모드

- `expo-router` 탭 그룹 `(tabs)/`: `index`(홈) · `watchlist` · `account`. 기본 탭 바 대신 시안의 떠 있는 유리 탭 바 + 검색 버튼(`FloatingTabBar`).
- 검색은 탭이 아닌 스택 화면 `search`. 열리면 아래가 홈 버튼 + 입력창으로 바뀌고 키보드가 바로 뜬다.
- 모드 상태 `useTradingMode()` — `paper`(기본) \| `live`. 내 계좌의 모의 \| 실전 분할 버튼이 바꾼다. 연동 계좌가 없으면 실전을 고를 수 없다(연동 안내로).
  기기에 저장하되, 앱을 새로 열면 실전 연동이 살아 있는지 다시 확인하고 아니면 모의로 돌린다.
- 상태 바 `StatusPill` — 모드 배지 + 실행 중 전략 수(`/api/quant/rulesets`의 RUNNING) + 미체결(모의 `/api/risk/exposure`, 실전 `/api/brokerage/orders/active`) + 실전이면 증권사 연동 상태.
  화면이 `statusBar="hidden"`을 넘기면 숨긴다.
- 기존 `alerts` 화면은 홈의 알림 진입(설정 아래 줄과 피드 "모두 보기")으로 옮긴다. 스크리너 홈은 검색 "많이 찾는 종목"과 홈 시장 요약으로 흡수한다.

### M3. 홈 피드 · 관심종목 · 검색

| 화면 | 데이터 |
|---|---|
| 시장 요약 | `/api/market/status`, `/api/market/indices` (`isMocked`면 "모의 값" 표시) |
| 내 종목 · 새 소식 | `/api/watchlists` + (로그인) `/api/paper/portfolio` 보유 ids → `/api/events/latest?stockIds=`, `/api/market/intraday?ids=`(스파크라인), `/api/quant/signals/feed`, `/api/risk/exposure`(한도 80% 이상이면 경고 카드). 클라이언트에서 시간순으로 합친다 |
| 새 소식 N건 | 위 피드에서 마지막으로 본 시각(기기 저장) 이후 항목 수 |
| 관심종목 | `/api/watchlists`, `/api/screener/quotes?ids=`(시세·거래량 배수·오늘 이벤트), `/api/market/intraday?ids=`, `/api/alerts/rules`(알림 켜짐 수). 추가·정렬·순서는 기존 쓰기 API |
| 검색 | `/api/stocks/search`, `/api/events/search`, `/api/quant/market/search`, `/api/news/search`(로그인) 병렬 → 범위별 개수. 많이 찾는 종목 = `/api/screener?sort=amount&limit=5`. 최근 검색어·최근 본 종목은 기기 저장 |

로그인 안 한 상태: 홈은 시장 요약 + 시장 전체 이벤트(`/api/events/recent`)를 보여 주고 "로그인하면 내 종목 소식만" 안내.

### M4. 종목 상세 · 모의 주문 시트

- 시세 `/api/stocks/{id}/price`, 차트 `/api/stocks/{id}/candles`(1분 `1m`, 15분 `15m`, 일 `1d`, 주 `1w`, 월 `1M`, 1년은 `1d` + `from` 365일). MA20은 화면에서 계산. 차트는 `react-native-svg`로 그린 선 + MA20 + 이벤트 점.
- 왜 움직였나: `/api/stocks/{id}/events`(windowChangePct·volumeMultiple) + `/api/stocks/{id}/disclosures` 최근 것.
- 보유 요약: 모의 `/api/paper/portfolio`의 그 종목. 없으면 줄을 감춘다.
- 주문 시트(`order` 모달, `presentation: "formSheet"`): `/api/wallet`(가용 현금 → 최대 수량), `/api/risk/preview`(입력이 바뀔 때 디바운스), `POST /api/paper/orders`(+ `X-Idempotency-Key`), 체결 뒤 `/api/paper/trades/{id}/emotion`.
  리스크 미통과면 버튼을 막고 이유를 보인다. 매수·매도 버튼과 강조는 상승·하락 색.

### M5. 내 계좌 · 전략 (모의)

- 총자산·막대·현금/예약금/평가액: `/api/wallet`. 오늘 손익: `/api/risk/exposure` `dailyPnl`.
- 보유: `/api/paper/portfolio`. 주문: 대기 중 지정가 `/api/paper/orders?status=PENDING` 계열과 조건부 `/api/paper/conditional-orders`, 취소는 확인 한 번.
- 최근 돈의 흐름: `/api/wallet/ledger`. 오늘의 리플레이: `/api/wallet/replay?date=`(요약 화면 하나).
- 전략: `/api/quant/rulesets` + RUNNING 전략의 `/forward-test`. 신뢰도 등급은 `backtest.reliabilityScore`. 성과 고지 문구 고정.
  전략 만들기·편집은 웹으로 보낸다(웹 주소 열기).
- 모의 계좌가 없으면(204) 시작 자금 고르기(1,000만·3,000만·1억, `POST /api/paper/account`).

### M6. 실전 모드

- 실전 분할: `/api/brokerage/account`(증권사·마스킹 계좌·`tokenValid`), `/api/brokerage/account/balance`(현금·평가·보유), 거래 중지 `/api/brokerage/trading-status`.
- 실전 화면 표시: 상태 바·배지·테두리 주황, 홈 부제 "보유 N종목", 종목 상세 하단 "실전 · 보유 N주".
- 실전 주문 시트: 주황 고지("한국투자증권 계좌로 바로 주문됩니다. 체결되면 되돌릴 수 없어요"), 최대 수량 = 증권사 현금 ÷ 가격, 리스크 줄은 웹 W2와 같이
  집중도만 화면 판정하고 나머지는 "서버 검증" + `/api/risk/exposure` 한도 표시(ADR-025: 실전 판단에 모의 데이터를 쓰지 않는다).
  **길게 누르기(1초, 진행 링 + 햅틱)** 로만 제출 → `POST /api/brokerage/orders`(+ `X-Idempotency-Key`). 서버 리스크 게이트(422)·킬 스위치(ADR-057)가 최종 방어선이고, 그 응답을 그대로 보여 준다.
  주문 결과가 불명(시간 초과)이면 "체결 여부 확인 중"으로 두고 `/orders/{id}/sync`로 다시 확인한다 — 다시 보내지 않는다.
- 다른 증권 계좌(파일·손 입력): 기존 `accounts.tsx`를 실전 쪽 아래 줄에서 연다(`/api/portfolio/*`는 #241·#245 머지 뒤 동작, 그 전에는 지금처럼 "준비 중").
- 이 단계는 PR 전에 `security-reviewer`로 본다.

### M7. 설정 · 로그인 · 온보딩

- 설정 목록(시안 그대로). 값: 모의 계좌 `/api/paper/account`, 증권사 연동 `/api/brokerage/account`, 구독 `/api/subscription/me`, 알림 채널·방해 금지·주간 리포트 `/api/users/me/notification-preferences`, 리스크 한도 `/api/risk/limits`, 동의 내역 `/api/users/me/consents`.
  편집이 복잡한 것(구독 결제·증권사 연동·리스크 한도 편집·동의 철회)은 웹 해당 화면을 연다. 토글(주간 리포트·푸시)은 앱에서 바로 바꾼다.
- 화면 모드(자동·라이트·다크, 기본 다크) · 상승·하락 색 화면(미리보기 포함).
- Face ID 잠금: `expo-local-authentication`. 켜면 앱이 앞으로 올 때 잠금 화면.
- 로그인: 시안 모양, 이메일만. 소셜 버튼은 감춘다. 가입·비밀번호 찾기는 지금처럼 웹.
- 온보딩 3장: 처음 실행 때 한 번(기기 저장), 건너뛰기 가능. 마지막 "시작하기" → 로그인 상태면 모의 계좌 만들기, 아니면 로그인.

## 4. 이 계획 밖 (백엔드가 필요한 것)

| 빈 곳 | 지금 처리 | 할 일 |
|---|---|---|
| 네이티브 소셜 로그인(카카오·네이버·Google), Apple 로그인 | 이메일만 | 네이티브 토큰 교환 API + Apple 제공자. App Store 정책상 다른 소셜 로그인을 넣으면 Apple 로그인도 같이 넣어야 한다 |
| 프로필(닉네임·이메일) | ✅ `GET /api/users/me`로 해결 — 설정 프로필 카드가 쓰고, 응답 전에는 로그인 이메일 | — |
| 실전 잔고 대조 | 조회 시각만 | 잔고 스냅샷·대조(ADR-106 2단계와 함께) |
| 주문 전 수수료·세금 추정 | 고지만 | 증권사별 요율 표 |
| 내가 구독한 전략 목록 | 신호 피드로 대신 | `/api/quant/market/subscriptions` |
| 상태 바 합계 | 쿼리 2~3개 | 필요하면 합친 엔드포인트 |

## 5. 검증

- 단계마다 `pnpm --filter @monticker/mobile exec tsc --noEmit`, `expo install --check`(CI와 같음).
- 이 Mac에는 Xcode가 없어 iOS 시뮬레이터를 못 쓴다. 대신 `expo start --web`(react-native-web)으로 390×844 화면을 헤드리스 브라우저로 그려
  다크·라이트를 시안과 나란히 본다. 블러·햅틱·Face ID·길게 누르기 같은 네이티브 동작은 웹에서 확인되지 않으므로, 실기기 확인 목록을 PR에 남긴다.
- 순수 로직(색 해석·최대 수량·피드 합치기·MA20)은 단위 테스트를 둔다(`vitest`, node 환경).

## 6. 진행 상황

단계마다 앞 PR 위에 쌓았다(앞 PR이 머지되면 다음 PR의 대상 브랜치를 main으로 바꾼다).

| 단계 | PR | 상태 |
|---|---|---|
| 계획·ADR-113 | [#265](https://github.com/polynomeer/monticker/pull/265) | 리뷰 중 |
| M1 테마·부품 | [#266](https://github.com/polynomeer/monticker/pull/266) | 리뷰 중 |
| M2 셸 + M3 홈·관심종목·검색 | [#267](https://github.com/polynomeer/monticker/pull/267) | 리뷰 중 — 셸만 따로 내면 임시 화면을 만들었다 버려야 해서 합쳤다 |
| M4 종목 상세·모의 주문 | [#268](https://github.com/polynomeer/monticker/pull/268) | 리뷰 중 |
| M5 내 계좌·전략(모의) | [#269](https://github.com/polynomeer/monticker/pull/269) | 리뷰 중 |
| M6 실전 모드 | [#270](https://github.com/polynomeer/monticker/pull/270) | 리뷰 중 — security-reviewer 반영(결과 불명 주문 잠금 등). 서버 멱등 필터 보강은 별도 작업 |
| M7 설정·로그인·온보딩 | [#271](https://github.com/polynomeer/monticker/pull/271) | 리뷰 중 |

### 계획과 달라진 것

- **기존 화면 색 바꾸기(M1)는 건너뛰었다.** M2~M5에서 화면을 다시 만들며 테마를 입혔다.
- **웹 미리보기:** `react-native-web`과 app.json `web` 플랫폼을 더했다. 브라우저에는 OS 보안 저장소가 없어 `secureStore.web.ts`가 refresh token을 메모리에만 둔다.
  `expo export -p web --dev`로 내보내야 한다(릴리스 빌드는 http API를 막는다, `api.ts`).
- **홈 "새 소식 N건"은 오늘(KST) 항목 수**다. 기기에 마지막으로 본 시각을 두는 대신 단순하게 갔다.
- **가격 알림 목록**(예전 하단 "알림" 탭)은 설정 › 가격 알림으로 옮겼다. 규칙 응답에 종목 이름이 없어 이름이 비던 문제도 고쳤다.
- **예약금 막대는 노랑**(시안은 주황) — 주황은 실전 전용.
- **설정 프로필 이메일**은 로그인할 때 OS 보안 저장소에 두고 로그아웃 때 지운다.
- **실전 모드에서는 홈의 일간 손실 경고를 띄우지 않는다**(모의 계좌 기준이라). 실전 내 계좌의 오늘 손익도 실전 원장이 없어 보이지 않는다.
