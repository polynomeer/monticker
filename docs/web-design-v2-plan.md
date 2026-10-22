# 웹 디자인 2판 적용 계획

> 읽을 때: 웹 디자인 시안(디자인 캔버스 "monticker 디자인 시안" v18)을 `apps/web` 코드에 옮길 때. 결정은 [ADR-112](decisions/112-web-shell-v2-search-rail-status-themes.md).

2026-10-20~21에 웹 디자인 시안을 리뷰하고 고쳤다. 이 문서는 그 시안을 코드에 옮기는 순서와 범위다. **기본 테마는 드라큘라(지금 팔레트)** 이고,
그래파이트 등 다른 테마는 설정에서 고르는 선택지다.

## 1. 시안에서 바뀐 것 (리뷰 요약)

| 영역 | 시안 결정 |
|---|---|
| 검색 | 모든 화면 상단 고정 입력창, 그 자리 미리보기, ⌘K = 포커스. 레일 "트레이딩" 삭제 |
| 상단 바 | 56px 한 줄 고정, 지표는 한 줄(넘치면 "+N 지표"), 종목 선택 버튼에서 돋보기·⌘K 제거 |
| 레일 | 아이콘 + 라벨, 홈 · 관심종목 · 탐색 · 내 자산 · 실전투자 \| 퀀트랩 · 전략 마켓 \| 설정. 알림·전체 메뉴 제거, 섹션 탭 |
| 상태 알약 | 계좌 칩(잔액) → 모드 배지 + 진행 중(전략·미체결). 실전은 주황 + 상단 주황 줄 |
| 색 역할 | 주황 = 실전 전용. 공시 청록, 주의 노랑, 색약 하락 자홍 |
| 안전 UX (리뷰 P0) | 주문 패널 모드 토글 제거, 실전 주문에도 리스크 검사, 한도 초과·현금 부족 주문 차단 표시, 조건부 주문 확인 창 + "감시 취소", 전략 카드 성과 고지·검증 전 표시 |
| 테마 | 드라큘라(기본) · 그래파이트 · 미드나이트 네이비 · 클린 라이트 · 웜 페이퍼 · 하이 콘트라스트 |
| 로고 | 차트 입 몬스터 — 이미 반영(#243) |

**시안과 다르게 가는 것:** 시안은 "내 계좌"가 모의·실전을 모두 품는 구조였다. 코드는 [ADR-115](decisions/115-assets-hub-absorbs-paper-portfolio.md)을
따라 "내 자산"(모의 포함 보기)과 "실전투자"(주문)를 따로 둔다. 시안의 내 계좌 섹션 탭 중 모의 쪽은 내 자산 탭으로, 실전 쪽은 실전투자 섹션 탭으로 옮긴다.

## 2. 단계 (단계마다 PR 하나)

선행 조건: **W4 셸은 ADR-115 작업(#245 내 자산 화면, #250 모의 포트폴리오 흡수)이 머지된 뒤** 시작한다. 같은 `Shell.tsx`의 레일을 고친다.
W1~W3는 셸과 겹치지 않아 먼저 한다.

### W1. 색 역할 — 주황은 실전 전용

- `designTokens`에 의미 토큰을 더한다: `live`(주황), `caution`(노랑), `disclosure`(청록). Tailwind에서 `text-live`처럼 쓴다.
- 주황 사용처(약 60곳)를 의미별로 옮긴다: 실전 배지·실전 경고·실주문 확인은 `live`, 공시(`EVENT_COLOR`·알림·차트 마커)는 `disclosure`,
  경고·주의·대기·"모의 데이터" 표식은 `caution`, 범례 같은 계열 색은 다른 계열 색으로.
- 색약 차트 테마(`themeStore` `classic`)의 하락색 `#f97316` → 자홍, 라벨 "상승 파랑 · 하락 자홍", 등락 ▲▼ 기호가 늘 붙는지 확인.
- 테스트: `brokerageInsights.test.ts`(`text-dracula-orange` 기대), `frontierChart.test.ts`(`#ffb86c` 기대) 갱신.

### W2. 안전 UX (리뷰 P0)

| 화면 | 파일 | 바꿀 것 |
|---|---|---|
| 종목 상세 주문 | `components/stock/OrderForm.tsx` | 모의/실전 토글 제거 → "모의투자 주문" 표시. 실전 주문은 실전투자 화면에서 |
| 실전 주문 | `app/brokerage/orders/page.tsx` | 제출 전에 `useRiskPreview`(`/api/risk/preview`)로 리스크 행 표시, 실패면 제출 비활성 + 이유. 검사 블록 제목 "주문 전 검사 · 모의·실전 공통" |
| 빠른 매수 | `components/portfolio/QuickTradePanel.tsx` | 섹터 비중 계산 대신 리스크 미리보기 결과로 차단·이유·"리스크 한도 보기" 링크 |
| 체결 엔진 | `components/matching/OrderForm.tsx` | 가용 현금 초과를 입력 즉시 표시(최대 수량), 제출 비활성 |
| 조건부 주문 | `app/brokerage/conditional-orders/page.tsx`, `ConditionalOrderRow.tsx` | 등록 전 확인 창(계좌·조건·지정가·수량·유효기간·갭 하락 고지), "해지" → "감시 취소"(확인 한 번), 탭 이름 "새 조건부 주문" |
| 전략 카드 | `strategy-market/MarketStrategyCard.tsx`, `quant/RuleSetCard.tsx`, `app/quant-lab/market/page.tsx` | 성과 고지(약관 `terms` §96 요구), 지표 순서 MDD → 포워드 기간 → CAGR, 백테스트/포워드 라벨, 검증 전 카드 흐리게 + "검증 전 · 표본 N주" |

백엔드 변경은 없다. 실전 주문의 서버 측 리스크 검사(422)는 그대로 최종 방어선이다.

### W3. 테마 토큰과 테마 선택

- `designTokens`의 `dracula`·`tm` 값을 CSS 변수로 옮긴다(`--tm-page` 등, RGB 채널). Tailwind 색은 `rgb(var(--tm-page))`로 바꾸고 클래스 이름은 유지한다.
- `globals.css`에 `:root`(드라큘라 기본)와 `html[data-theme="graphite" | "navy" | "light" | "paper" | "contrast"]` 값을 둔다. 값은 시안 "테마 시안" 페이지의 팔레트.
- 테마 저장: `themeStore`에 `appTheme`(기본 `dracula`)를 더하고 `ThemeProvider`가 `data-theme`를 단다. `forcedTheme="dark"`는 라이트 테마 때 `color-scheme`만 바꾸도록 정리.
- 설정 › 화면에 테마 선택 카드 6개(미리보기 포함). 기존 "고대비" 접근성 옵션과 하이 콘트라스트 테마의 관계를 정리한다(고대비 = 하이 콘트라스트 테마 선택으로 통합 검토).
- 하드코딩 hex(차트 어댑터 `EChartsAdapter.tsx` 21곳, `charts.tsx`, `VolumeChart`, `DepthChart` 등)는 `readThemeToken()`으로 옮긴다. 이번 단계에서 다 못 옮기면 남은 곳을 표로 남긴다.

### W4. 셸 — 검색·상단 바·레일·상태 알약 (ADR-115 머지 뒤)

- 검색: `StockSearch.tsx`의 로컬 `useSearch`·디바운스를 `hooks/useGlobalSearch.ts`로 꺼낸다(종목·이벤트·전략). 셸에 `HeaderSearch`(combobox, 미리보기 그룹, ↑↓·Enter·Esc, "전체 결과" 링크).
  ⌘K는 입력창 포커스로 바꾼다. `SearchPill`은 지운다.
- 상단 바: `flex-nowrap` 56px, 지표 칸 `overflow-hidden` + 지표 `flex-none`, 오른쪽 묶음 `ml-auto`. "+N 지표"는 `ResizeObserver`로 넘치는 지표만 접는다.
- 레일: 라벨 레일(폭 72px), 항목은 ADR-112. 섹션 탭 컴포넌트(`SectionTabs`)를 만들고 묶인 화면들이 본문 첫 줄에 단다.
- 상태 알약: `AccountChip` → `StatusPill`. 모드 배지 + 실행 중 전략 수(자동 주문 규칙/전략 실행 쿼리) + 미체결 수(모의 주문·실계좌 주문 쿼리). 데이터가 없으면 배지만.
  `useAccountChipSummary`의 잔액 표시는 펼침 메뉴로 옮긴다(`accountChip.test.tsx`는 훅 테스트라 유지).
- 실전 화면 상단 주황 줄.
- e2e: `e2e/screener*.spec.ts`는 "실시간 스크리너" 제목에 기대므로 섹션 탭이 들어가도 제목을 유지한다.

### W5. 마무리

- 리뷰 P1·P2 중 남은 것: 포커스 링(`:focus-visible`) 전역, 입력 테두리 대비 3:1, 작은 하락 숫자 대비, 닫기 버튼 24px, 빈·로딩·오류 상태 보강, "해지" 등 용어 정리.
- 화면 숫자 정합성(같은 원장에서 파생)은 시안 문제였고 코드에서는 이미 한 출처라 점검만 한다.

## 3. 검증

- 단계마다 `pnpm --filter @monticker/web exec tsc --noEmit`, 관련 vitest, 로컬 dev 서버에서 해당 화면을 헤드리스 브라우저로 렌더링해 확인한다.
- W3·W4는 1440·1280px 두 폭에서 상단 바가 한 줄인지, 테마별로 대비가 기준(본문 4.5:1, 입력 테두리 3:1)을 넘는지 본다.

## 4. 진행 상황

| 단계 | PR | 상태 |
|---|---|---|
| W1 색 역할 | [#261](https://github.com/polynomeer/monticker/pull/261) | 리뷰 중 |
| W2 안전 UX | [#262](https://github.com/polynomeer/monticker/pull/262) | 리뷰 중 |
| W3 테마 | [#263](https://github.com/polynomeer/monticker/pull/263) (#261 위에 쌓음) | 리뷰 중 |
| W4 셸 | — | ADR-115 머지 대기 |
| W5 마무리 | — | 대기 |

### W3 남은 하드코딩 (라이트 계열 공개 전에 처리)

| 대상 | 지금 | 처리 |
|---|---|---|
| 임의값 hex 클래스(`bg-[#3a2f52]` 등) | 58개 파일 164건, `text-white`·gray 7건 | 의미 토큰(`tm-*`·`caution` 등)으로 바꾼다. 특히 `terminal/ui.tsx`의 태그·알림 박스 배경 |
| Frontier 차트(`FrontierChart`·`frontierOption`) | 드라큘라 고정 | `useThemePalette()`로 |
| 차트 범례(`ChartPanel`)·이벤트 메타(`stock/parts.tsx` `EVENT_META`)·`alerts/data.ts` | 드라큘라 고정 | 토큰으로 |
| `EventChartIllustration`·`AuthShell`·`Toast`·`CookieBanner` | 드라큘라 고정 | 토큰으로 |
| 실전 주문 리스크 미리보기 | 종목 집중도만 화면 판정(W2) | 증권사 잔고 기준 미리보기 API + ADR-092 보완 ADR (백엔드) |
