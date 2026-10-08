# 디자인 시안 적용 후속 — 기능 구현 계획서

> 읽을 때: 웹 화면에서 "준비 중" 표식이 붙은 요소를 실제 기능으로 바꿀 때, 또는 다음 스프린트 범위를 정할 때.

2026-10 트레이딩 터미널 디자인 시안을 웹 전체 34개 화면에 적용했다([ADR-066](decisions/066-terminal-ui-shell.md)). 시안에는 있지만
백엔드나 데이터가 아직 없는 요소는 **화면만 먼저** 넣었다. 그런 요소는 아래 원칙대로 표시하고, 실제 기능으로 바꿀 항목을 이 문서에 모았다.

- **지어낸 숫자를 실데이터처럼 보여주지 않는다.** 데이터가 없으면 `—`나 빈 상태를 보여준다. 동작하지 않는 컨트롤은 비활성으로 둔다.
- 패널 헤더나 컨트롤 옆에 **"준비 중" 표식**(`Panel preview`, `<PreviewTag/>`)을 단다.
- 실계좌 화면에서는 검증되지 않은 경로로 주문을 보낼 수 있는 컨트롤을 만들지 않는다.

항목을 구현하면 행을 지우지 말고 `상태`를 ✅로 바꾸고 커밋·ADR 링크를 단다. 우선순위는 다음과 같다.

| 우선순위 | 뜻 |
|---|---|
| **P0** | 상용 출시 전 필수: 법무·보안·실거래 안전 |
| **P1** | 핵심 가치(이벤트·퀀트·지갑)를 실데이터로 채우는 항목 |
| **P2** | 편의·완성도 |

---

## 0. P0 — 출시 전에 반드시

| # | 화면 | 항목 | 지금 | 필요한 것 | 상태 |
|---|---|---|---|---|---|
| 0-1 | /signup | 약관·개인정보·연령 동의 기록 | 화면에서는 필수 동의를 체크하지 않으면 가입을 막는다. 하지만 **서버에 기록되지 않는다** | `SignupRequest`에 동의 항목·약관 버전·시각을 추가하고 `user_consents` 테이블을 만든다. 철회 이력도 남긴다 | ✅ [ADR-068](decisions/068-consent-records.md) — 가입 시 서버 검증·기록, 소셜 가입·약관 개정은 `/consent` 게이트 |
| 0-2 | /brokerage/connect | 연동 동의 3개(위임·자금 미보관·손실 귀속) 기록 | 3개를 모두 체크해야 연동 버튼이 켜진다. 서버 기록은 없다 | connect 요청에 동의 버전·시각을 저장한다(법무 증빙). 0-1과 같은 consent 모델을 쓴다 | ✅ [ADR-068](decisions/068-consent-records.md) — 증권사 호출 전 검증, 같은 트랜잭션에 기록 |
| 0-3 | /brokerage/connect | 연동 해지(API 키 즉시 파기) | 보안 설명 패널에 "준비 중"으로 표시. **해지 API가 없다** | `DELETE /api/brokerage/account`: 키 파기, 계좌 비활성화, 조건부 주문 정리. 프론트는 확인 단계를 둔다. 약관 제3조("해지 시 즉시 파기")와 지금 동작이 어긋난다 | ✅ [ADR-067](decisions/067-brokerage-disconnect.md) — 결과가 열린 주문이 있으면 거부. 갈아타기로 비활성화된 계좌의 키 정리는 후속 |
| 0-4 | /quant-lab/earnings | 출금 안내(본인 명의·지급 기한·원천징수) | 확인되지 않은 정책이라 "운영 검토 후 지급, 정산 정책을 따름"으로 완화해 두었다 | 정산·원천징수 정책 확정, 법무 검토, 예금주 본인확인을 서버에서 검증 | 정책·법무 결정 대기 — 코드로 풀 수 없음 |
| 0-5 | /settings/notifications | 리스크 경고·"결과 확인 중" 주문 알림 | 비활성 토글 | 알림 발송 경로를 [ADR-065](decisions/065-user-notifications-from-api.md)의 `notify.user`로 연결한다. 결과 불명 주문([ADR-056](decisions/056-brokerage-order-unknown-outcome.md))은 방해 금지 시간에도 전달한다 | 부분 ✅ — ‘결과 확인 중’: 결과 불명 진입·확정·수동 검토 때 `notify.user`로 알림(`OrderOutcomeNotices`), 끌 수 없음. 리스크 한도 근접 경고는 주기 평가가 필요해 아래 P1로 분리 |

이번 작업 중 별도로 발견한 보안 결함: **감정 태그 IDOR.** `EmotionTagService.getTag/saveTag`가 거래 소유자를 확인하지 않아,
다른 사용자의 감정 태그와 메모를 읽거나 덮어쓸 수 있다. 별도 작업으로 분리했다.

---

## 1. 공통(셸·키트)

| 항목 | 지금 | 필요한 것 | 우선순위 | 상태 |
|---|---|---|---|---|
| 상단 바 "레이아웃 편집"·"전체 메뉴" 버튼 | 비활성 아이콘 | 패널 배치 저장(사용자별, 서버 또는 localStorage), 전체 메뉴 시트 | P2 | |
| 패널 헤더 + / 설정 / 확대 아이콘 | 아이콘만 있고 동작 없음. 내보내기는 일부 화면(포트폴리오·지갑)만 CSV로 동작 | 패널 확대 모달, 탭 추가, 패널별 설정을 키트 `Panel`에 구현 | P2 | 부분 ✅ [fa542cff](https://github.com/polynomeer/monticker/commit/fa542cff) 확대: 확대 아이콘이 있는 모든 패널이 그 자리에서 화면 위 대화상자로 뜬다(Esc·닫기·배경 클릭, 포커스 가둠·복귀, 내용 재마운트 없음). 설정 아이콘은 패널마다 내용이 달라 키트에서 일반화할 수 없어 비활성 "준비 중"으로 둠. + 는 화면이 `onAction`으로 처리할 때만(스크리너 저장) |
| 상단 계좌 칩 잔액 | 잔액 없이 "모의투자 계좌 / 실전 계좌"만 표시 | 모의 총자산·실계좌 가용 현금 요약 조회를 셸에서 공유 | P2 | ✅ [2c9e39cc](https://github.com/polynomeer/monticker/commit/2c9e39cc) 모의 = `/api/wallet` 총자산(/wallet과 같은 쿼리, 30초), 실전 = 연동된 계좌가 있을 때만 실전 화면에서 `/api/brokerage/account/balance` 가용 현금(주황). 로딩·오류는 `—` |
| 라이트·시스템 테마 | 다크 고정(`forcedTheme`) | 토큰을 CSS 변수로 바꾸고 테마별 값 세트를 둔다([ADR-066](decisions/066-terminal-ui-shell.md) Revisit) | P2 | |
| 리스크 한도 근접 경고(80%) | 없음(P0-5에서 분리) | VaR·집중도·일간 손실 사용률을 주기적으로 평가해 임계값을 넘는 순간 한 번 알림(사용자·규칙·일자별 dedupKey). 차단된 조건부 주문은 이미 ADR-065로 알린다 | P1 | ✅ [ADR-070](decisions/070-risk-limit-near-warnings.md) — 모의계좌, 5분 주기, 항목·일자별 1회, 끌 수 없음(실계좌는 잔고 캐시 필요해 보류) |
| 고대비 모드 | `a11yStore.highContrast`는 연결돼 있다. 하지만 globals.css 규칙이 옛 토큰(`dracula-comment`)만 덮어 새 화면에서는 효과가 약하다 | `tm-muted`·`tm-line2` 등 새 토큰용 고대비 규칙 | P1 | ✅ tm-* 토큰 고대비 규칙 |
| 접근성 설정: 글자 "작게", 움직임 줄이기, 숫자 고정폭, 가격 깜빡임 | 비활성 | `a11yStore` 필드를 추가하고 ECharts 애니메이션·`.num` 글꼴을 설정값에 연동 | P2 | ✅ [d9e364a2](https://github.com/polynomeer/monticker/commit/d9e364a2) 글자 작게(87.5%), 움직임 줄이기(기본은 OS `prefers-reduced-motion`, 켜면 CSS 전환과 차트 툴팁·십자선 전환을 어댑터 prop으로 끔), 숫자 고정폭 끄기(본문 글꼴+tabular-nums), 가격 깜빡임(`FlashValue`, 종목 화면 현재가 — [2c9e39cc](https://github.com/polynomeer/monticker/commit/2c9e39cc)). 이 브라우저에 저장 |
| 고정폭 숫자 글꼴 | 시스템 모노 스택(OS마다 다름) | 모노 웹폰트를 self-host(CSP `font-src 'self'`)할지 결정 | P2 | |

## 2. 마켓

### / 홈
| 항목 | 지금 | 필요한 것 | 우선순위 | 상태 |
|---|---|---|---|---|
| 지수 카드(KOSPI·KOSDAQ·USD/KRW)와 상단 지수 스탯 | `—`, "지수 시세 준비 중" | 지수·환율 시세 수집(worker)과 `GET /api/market/indices` | P1 | 부분 — 수집·저장·API·화면 완료, 공급자는 Mock("모의" 표시). KIS 업종지수 규격 확인·환율 출처 결정 필요 ([ADR-071](decisions/071-market-index-quotes.md)) |
| 상단 "오늘 이벤트"·"급등·급락" 건수 | `—` | 당일 이벤트 유형별 집계 API(`/api/events/recent`는 최대 50건이라 집계 불가) | P2 | ✅ `GET /api/events/summary` — KST 하루 유형별 건수, 급등·급락은 종목 수 ([ADR-087](decisions/087-event-and-sector-aggregates-at-query-time.md)) |
| 장 상태 | 평일 09:00–15:30 KST로 클라이언트에서 계산 | 휴장일 캘린더 API | P2 | ✅ [ADR-086](decisions/086-krx-trading-calendar.md) — `GET /api/market/status`·`/api/market/calendar`, 휴장일 이름 표시, 요청 실패 시 클라이언트 규칙. 수능일 시간 변경은 범위 밖 |
| 이벤트 피드 "이벤트 구간 변동"·"거래량 배수" 열 | `—`(시각·종목·유형·중요도는 실데이터) | `StockEventResponse`에 구간 변동률·거래량 배수 추가 | P1 | ✅ |
| 퀀트 시그널 패널 | 빈 상태 + 퀀트랩 링크 | 내 전략·구독 전략 시그널을 모으는 피드 API | P1 | ✅ `/api/quant/signals/feed` |
| 섹터 히트맵 등락률 | 섹터별 24시간 이벤트 수로 색의 진하기를 대신함 | 섹터별 등락률 집계 API | P2 | ✅ `GET /api/screener/sectors/performance` — 스크리너와 같은 등락률 식, 동일가중 평균, 오늘 이벤트 수는 보조 ([ADR-087](decisions/087-event-and-sector-aggregates-at-query-time.md)) |
| 테마 탭 | 빈 상태 | 테마 분류 데이터와 API | P2 | |
| 시안에 없어 홈에서 뺀 위젯(포트폴리오 스냅샷, 최근 본 종목 스트립, 관심종목 티커) | 제거 | 다른 화면이나 패널로 재배치할지 결정(최근 본 종목은 검색 화면에 남아 있음) | P2 | |

### /screener
| 항목 | 지금 | 필요한 것 | 우선순위 | 상태 |
|---|---|---|---|---|
| 섹터 칩 필터 | 표시만 | `/api/screener`에 `sector` 파라미터 | P1 | ✅ [ADR-072](decisions/072-screener-criteria-and-saved-screens.md) |
| 등락률·거래량 배수 범위 슬라이더 | 비활성 모형 | `minChange/maxChange`, `minVolMult` 파라미터와 거래량 배수 계산 | P1 | ✅ 거래량 배수 = 최신 일봉 ÷ 직전 20일 평균 |
| 이벤트 체크박스(뉴스·공시 동반, 퀀트 시그널, 감성 급변) | 비활성 | 스크리너 쿼리에 당일 이벤트를 조인하는 필터 | P1 | ✅ |
| 조건 저장 + 저장된 스크린 탭 | 버튼 비활성. 탭은 기존 실시간/급등·급락/외국인·기관 | 저장 스크린 CRUD API와 실행 | P1 | ✅ 사용자당 20개 |
| 결과 열 거래량 배수·오늘 스파크라인·이벤트, 상단 평균 거래량 배수·이벤트 동반 | `—` | 응답에 거래량 배수·당일 이벤트 추가, 장중 미니 시계열 일괄 API | P1 | ✅ `/api/market/intraday` |
| 시장 세그먼트 KOSPI/KOSDAQ | 동작하는 전체/국내/해외로 대체 | `ScreenerRepository`에 kospi/kosdaq 구분 추가 | P2 | ✅ `market=kospi`·`kosdaq` — 화이트리스트(`ScreenerCriteria.MARKETS`)와 고정 SQL 조각. 화면은 "국내"를 고르면 국내 전체/코스피/코스닥 하위 세그먼트 |
| 시가총액 범위 | 기존 대형/중형/소형 구간 | 필요하면 `minCap/maxCap` | P2 | |

### /stocks/[symbol] 트레이딩
| 항목 | 지금 | 필요한 것 | 우선순위 | 상태 |
|---|---|---|---|---|
| 주문 유형 "지정가" | 비활성 옵션. 시장가만 동작 | 모의 주문 API(`/api/paper/buy·sell`)는 시장가만 받는다. 지정가 매칭엔진(`/api/matching`)은 별도 가상계좌라 통합 방식을 정하는 ADR이 필요 | P1 | ✅ [ADR-074](decisions/074-paper-limit-orders-and-fill-sweeper.md) |
| 익절/손절 + "체결 시 자동 등록", 주문 패널 "조건부" 탭 | 비활성 + 실전 조건부 주문 링크 | 모의계좌용 조건부 주문 백엔드 | P1 | ✅ [ADR-075](decisions/075-paper-conditional-orders.md) |
| 호가 "체결" 탭(틱) | 준비 중 | 실시간 체결 틱 조회/WS | P1 | ✅ 메모리 링 버퍼(재시작 시 비었다가 다시 참) |
| 타임프레임 3분/15분/1시간 | 비활성 | `candles_3m/15m/1h` 집계와 `CandleService` interval | P1 | ✅ [ADR-076](decisions/076-query-time-candle-aggregation.md) |
| 이벤트 레이어 "퀀트 시그널"·"감성" | 비활성 칩 | 종목별 포워드 테스트 시그널 조회, `sentimentScore` 기반 레이어 | P1 | ✅ |
| 상단 시가총액·거래대금 | screener quotes. 펀더멘털이 모의 데이터면 `—` | KIS 펀더멘털 실데이터 연동 | P1 | 보류 — 코드 경로는 있음, 실제 KIS 키와 단위 검증 필요 |
| 계좌 세그 "실전 · 연동 필요" | 항상 비활성. 이 폼은 실주문을 보내지 않는다 | 종목 화면 실주문은 [ADR-055](decisions/055-price-provenance-gate-for-real-orders.md)/[056](decisions/056-brokerage-order-unknown-outcome.md) 경로 정리 후 별도 설계 | P2 | |
| 주문 전 리스크 체크 | "점검하기" 버튼으로 `POST /api/risk/check` 호출(감사 로그가 남아 자동 호출하지 않음) | 감사 로그 없는 미리보기 전용 엔드포인트가 있으면 입력할 때마다 점검 | P2 | |
| 감정 태그 "계획대로" | `OTHER` + 메모 "계획대로"로 저장 | `EmotionType`에 `PLANNED` 추가 | P2 | ✅ [ADR-085](decisions/085-paper-order-entry-origin.md) — `PLANNED`로 저장, 기존 `OTHER`+"계획대로" 행은 V82가 이관 |
| 호가 "체결강도"·"내 주문" 표시 | 생략 | 체결강도 데이터, 모의 지정가 도입 후 | P2 | |
| 이벤트 패널 "호가 깊이" 탭 | 준비 중 | 프론트만: 기존 호가로 누적 깊이 차트 | P2 | ✅ 호가 패널과 같은 쿼리(추가 요청 없음)를 누적. 차트는 어댑터 뒤(`components/stock/chart/DepthChart`), 매수=상승색·매도=하락색(차트 테마). 보이는 호가 단계만의 누적이다 |
| 차트 유형 버튼, 그리기 도구(펜·텍스트·측정·확대·자석·잠금) | 비활성. 십자선·추세선·수평선·숨기기·지우기는 동작 | `EChartsAdapter` 드로잉 확장 | P2 | |
| 하단 보유 종목 "최근 이벤트"·"진입 경로" | `—` | 종목별 최근 이벤트 조회, 체결에 진입 출처(Watch Rule/전략/직접) 기록 | P2 | ✅ [ADR-085](decisions/085-paper-order-entry-origin.md) — `GET /api/events/latest` 일괄 조회, 가장 최근 매수 체결의 출처. 판정 불가 과거 거래는 `—` |

### /stocks/search · /compare · /watchlist · /alerts
| 화면 | 항목 | 지금 | 필요한 것 | 우선순위 | 상태 |
|---|---|---|---|---|---|
| /stocks/search | 많이 찾는 종목 | 거래대금 상위로 대신 표시 | 검색 로그 집계 API | P2 | |
| /stocks/search | 전략 검색 | `/api/quant/market` 상위 50개를 클라이언트에서 필터 | 서버 측 전략 검색 | P2 | |
| /compare | 베타(KOSPI) | `—` | KOSPI 지수 일봉 | P1 | ✅ (지수가 모의면 표시) |
| /compare | 배당수익률 | `—` | 배당 데이터 소스 | P2 | |
| /compare | 이벤트 수 | 종목당 100건까지만 조회해 "100+" | 이벤트 count API | P2 | ✅ `GET /api/events/counts` — 여러 종목 한 번에, 정확한 건수. 겹침 점·이벤트 후 평균은 여전히 최근 100건 ([ADR-087](decisions/087-event-and-sector-aggregates-at-query-time.md)) |
| /watchlist | 오늘(장중 스파크라인)·거래량 배수 열 | `—` | 장중 시계열 일괄 API, 거래량 배수 | P1 | ✅ |
| /watchlist | 정규장/시간외/NXT | 동작하는 전체/국내/해외로 대체 | 세션별 시세 | P2 | |
| /watchlist | 순서 이동 | ⋯ 메뉴에 "순서 이동(준비 중)" | 정렬 순서 변경 API(`PATCH sortOrder`) | P2 | ✅ [ADR-088](decisions/088-watchlist-order-group-row-lock.md) — `PATCH /api/watchlists/items/{id}/sort-order`, 그룹 행 잠금 + 0..n-1 재번호(V85). ⋯ 메뉴 위로/아래로(키보드 가능), "내 순서" 정렬에서만 |
| /watchlist | 외국인 순매수 | `—` | 투자자별 매매동향 | P2 | |
| /watchlist | 52주 최고/최저 | 1년 일봉으로 계산. 데이터가 300일 미만이면 `—` | 종목 기본정보에 52주 고저 추가 | P2 | ✅ 관심종목 응답 `range52w` — candles_1d, KST 오늘-52주 자정부터 한 쿼리로 일괄. 52주를 다 덮지 못하면 있는 기간의 고저와 실제 기간을 표시 |
| /alerts | 읽지 않음 표시·탭·"모두 읽음", 상단 "읽지 않음" | 탭에서 제외, 비활성, `—` | `alert_histories.read_at`과 읽음 처리 API | P1 | ✅ [ADR-073](decisions/073-alert-read-state-and-rule-pause.md) |
| /alerts | 규칙 켜기/끄기 토글 | 켜짐 상태로 비활성 표시 | 규칙 재활성화 API(지금은 DELETE 비활성화만 있음) | P1 | ✅ |
| /alerts | 시그널 탭 | 필터는 동작하지만 항상 비어 있음 | 퀀트 시그널을 알림 이력에 적재 | P2 | ✅ [ADR-090](decisions/090-quant-signal-alert-history-fanout.md) — 신호 커밋 후 alert 모듈이 주인·구독자(ADR-035) 각자의 이력에 적재(사용자·신호 유니크, 재전달 멱등). 푸시·이메일은 같은 트랜잭션의 `notify.user` 명령으로, 알림 설정 "퀀트 시그널"을 따른다. 이전 신호 백필 없음 |
| /alerts | 새 알림 규칙 | 종목 검색으로 이동 | 알림 화면에서 바로 만드는 폼(기존 `POST /api/alerts/rules`) | P2 | ✅ 알림 규칙 패널 안 폼 — StockPicker 종목 검색, 서버 필수 조건 + 범위 검사, 400·429 등 서버 오류 표시 |
| /alerts | 전달 채널 | `—` | 알림 채널 설정 조회 API | P2 | ✅ [ADR-093](decisions/093-notification-quiet-hours-and-delivery-channels.md) — `GET /api/users/me/notification-preferences/channels`: 종류별 푸시·이메일·대체 이메일·이력, 끌 수 없는 종류는 방해 금지 시간에도 즉시. worker 발송 정책과 같은 사례표로 테스트. 알림 생성은 없는·비활성 종목을 400으로 거부 |

## 3. 모의투자 · 지갑

| 화면 | 항목 | 지금 | 필요한 것 | 우선순위 | 상태 |
|---|---|---|---|---|---|
| /wallet, /settlement | "잔액 불일치 N건" | `—` | `LedgerReconciliationService` 결과 조회 API(`/api/wallet/reconciliation`) | P1 | ✅ `/api/wallet/reconciliation`(스냅샷 기준) |
| /wallet/replay | 캔들 리플레이 + 재생 컨트롤 | 실제 B/S 주문 마커만 표시, 컨트롤 비활성 | 과거 일자 분봉 조회 API(날짜 지정), 재생 엔진(프론트), 이벤트에 stockId | P1 | ✅ |
| /portfolio | 거래 내역 "경로"(직접/Watch Rule) | `—` | `/api/paper/history`에 `source`·`watchRuleId` | P1 | ✅ |
| /matching | 시장 전체 체결 테이프 | 내 체결만 표시 | 종목별 시장 체결 조회/스트림 | P1 | ✅ |
| /watch-rules | 퀀트랩 전략 신호를 감지 조건으로, 규칙 이름, 복합 조건 | 이벤트 3종(거래량 급증·급등·급락)만 | Watch Rule에 전략 시그널 소스·이름·복합 조건 필드 | P1 | ✅ [ADR-077](decisions/077-watch-rule-signals-compound-daily-limit.md) |
| /watch-rules | 하루 최대 발동 한도, 카드의 "오늘 발동 / 한도" | 입력 비활성, 오늘 발동 횟수만(실데이터) | 규칙별 일일 한도 필드와 서버 집행 | P1 | ✅ |
| /risk | 섹터 집중도 한도 | 섹터 비중은 실계산, 한도는 "미설정" | `SECTOR_CONCENTRATION` 규칙(RiskLimit 컬럼, RiskChecker) | P1 | ✅ [ADR-069](decisions/069-risk-limit-changes-cooling-off.md) |
| /risk | 리스크 체크 활성화 토글 | 현재 값만 표시. 예전 화면은 체크해도 실제로 바뀌지 않았다 | `PUT /api/risk/limits`에 `isActive` 추가 | P1 | ✅ 모의투자에만, 끄기는 24시간 뒤 |
| /risk | 한도 완화 쿨링오프("24시간 뒤 적용") | 문구 자체를 뺐다(사실이 아님) | 완화 지연 적용. 실거래 전에 필요할 수 있다 | P1 | ✅ [ADR-069](decisions/069-risk-limit-changes-cooling-off.md) |
| /risk | 차단·경고 기록, 상단 "이번 달 차단" | 빈 상태, `—` | 리스크 게이트 판정 이력 저장·조회(`/api/risk/decisions`) | P1 | ✅ 차단 기록(경고 모드는 P2) |
| /portfolio | 상단 "벤치마크 대비 KOSPI" | `—` | 지수 기간 수익률 API | P2 | |
| /portfolio | 거래 내역 감정 칸 | 실데이터. 단, 거래마다 개별 조회(N+1) | history 응답에 emotion 포함 | P2 | ✅ [ADR-085](decisions/085-paper-order-entry-origin.md) — 내역 응답에 emotion·memo·출처 포함 |
| /portfolio | 평균단가 차트 매수 마커 | 평균단가선만 | StockChart에 거래 마커 prop | P2 | ✅ [2f336bf1](https://github.com/polynomeer/monticker/commit/2f336bf1) `trades`/`interval` prop(KST 봉 버킷, 같은 봉 체결은 건수로 묶음), [d44d1a6a](https://github.com/polynomeer/monticker/commit/d44d1a6a) 모의 체결 매수·매도 마커(history 100건 단위 일괄 조회, 최근 500건까지) |
| /matching | 가격별 주문 대기열 조각, "내 주문 · 대기 N번째" | 잔량 막대 하나, "내 주문 · N주 대기" | 호가 API에 가격별 주문 큐와 내 순번 노출 | P2 | |
| /matching | 상단 평균 슬리피지·지연 | `—` | 주문 시점 최우선호가 저장 → 체결가 비교 집계, 엔진 처리시간 메트릭 | P2 | ✅ [ADR-091](decisions/091-wallet-behavior-and-execution-quality-metrics.md) — V88 접수 시점 최우선 호가(KIS 실시간만)·접수 시각, `/api/matching/execution-quality`(최근 30일, 수량 가중 bp, 시장가 지연 중앙값). 호가 기록 없는 체결은 빼고 건수 표시 |
| /wallet | 원장 행의 돈 흐름(예약금 → 정산 대기)과 출처 | 확실한 유형만 흐름 표시 | 원장 이벤트에 from/to 버킷과 주문 출처 | P2 | 부분 ✅ [ADR-085](decisions/085-paper-order-entry-origin.md) — 체결 행에 주문 출처 표시. from/to 버킷은 남음 |
| /wallet | 영수증 "예약금 잠금" 단계 | 접수 → 체결 → 정산 3단계(모의 즉시체결) | 지정가 영수증 API가 생기면 연결 | P2 | |
| /wallet | 감정 태그 "조급함"·"계획대로" | 백엔드 `EmotionType` 10종으로 표시 | enum 확장과 마이그레이션 | P2 | ✅ [ADR-085](decisions/085-paper-order-entry-origin.md) — `PLANNED`·`IMPATIENT`(12종), V82 데이터 이관 |
| /wallet | 점수 카드 세부 지표(계획 준수율, 손절 준수율, 지난주 대비) | 등급 + 피드백 목록 | `/api/wallet/score` 세부 지표 | P2 | ✅ [ADR-091](decisions/091-wallet-behavior-and-execution-quality-metrics.md) — `details`: 이번 주(KST 월~) 계획 준수율(ADR-085 같은 함수)·손절 준수율(손절을 정한 손실 매도만, 처음 손절선 기준)·지난주 대비. 분모 0이면 `—` |
| /wallet/replay | 계획 준수율, 계획 외 주문 | `—` | 주문별 계획 여부 집계 | P2 | ✅ [ADR-085](decisions/085-paper-order-entry-origin.md) — 계획 = 출처 Watch Rule·조건부·전략 또는 `PLANNED` 태그. 정의는 툴팁 |
| /wallet/replay | 주문 복기 행의 감정 칩·코멘트 | 종목·방향·수량·수익률만 | ReplayEvent에 tradeId·emotion·memo. 코멘트는 AI 생성 | P2 | 부분 ✅ [ADR-085](decisions/085-paper-order-entry-origin.md) — tradeId·감정 칩·메모·출처(일괄 조회). AI 코멘트는 남음 |
| /wallet/replay | "감정 분포 · 이번 주"·날짜별 % | 전체 기간 분포, 일별 손익 금액 | emotion-analysis 기간 파라미터, 일별 수익률 API | P2 | ✅ [ADR-091](decisions/091-wallet-behavior-and-execution-quality-metrics.md) — `emotion-analysis?from&to`(체결일 기준, 최대 1년, 한 쿼리), `/api/wallet/daily-returns`(그날 손익 ÷ 00:00 평가자산, 입출금 제외, 초기화 이전·시세 없음은 `—`). 날짜 띠 요청 6 → 1 |
| /settlement | 이번 주 순액, 공휴일, "완료" 서버 필터 | 정산 예정만 표시, 주말만 건너뜀, 현재 페이지 필터 | 기간별 정산 집계, 영업일 캘린더, `?status=` | P2 | ✅ [ADR-086](decisions/086-krx-trading-calendar.md) — `/api/settlement/paper/summary`(이번 주 순액), KRX 휴장일 반영(T+2·정산 캘린더·다가오는 휴장일), `?status=SETTLED`. 기존 PENDING 정산일은 기동 시 재정렬 |
| /watch-rules | 관심종목 그룹 단위, 주문 유형·금액(계좌 %) | 비활성, 시장가 고정, 수량(주) | 규칙 대상 그룹, 지정가·비율 수량 | P2 | |
| /watch-rules | 상단 "규칙 경유 손익" | `—` | 규칙 체결 손익 집계 | P2 | ✅ [ADR-085](decisions/085-paper-order-entry-origin.md) — 규칙이 낸 매도 체결의 실현 손익(이동평균), 합계·규칙별 |
| /risk | 한도 초과 시 "경고 후 진행" 모드, VaR 단위(원/%) | "주문 차단" 고정, % 입력 + 원화 환산 병기 | 정책 결정과 severity 처리 | P2 | |

## 4. 퀀트랩 · 전략 마켓

| 화면 | 항목 | 지금 | 필요한 것 | 우선순위 | 상태 |
|---|---|---|---|---|---|
| /quant-lab | 카드 "포워드 일치율" | `—` | 포워드 신호와 백테스트 신호의 일치율 지표·API | P1 | ✅ [ADR-078](decisions/078-forward-match-rate-and-list-performance.md) |
| /quant-lab | 카드 CAGR·MDD·곡선 | 실데이터. 단, 카드마다 백테스트 조회(N+1) | 룰셋 목록에 최신 백테스트 요약과 다운샘플 곡선 포함 | P1 | ✅ |
| /quant-lab/builder | 뉴스 감성·공시 유형·시가총액·배당 블록 | 비활성 | 퀀트 엔진 지표 추가(감성·공시·펀더멘털 연동) | P1 | 부분 — 감성·공시·배당 공시 ✅, 시가총액은 일별 이력 필요 ([ADR-079](decisions/079-quant-engine-aux-indicators-hard-exits-costs.md)) |
| /quant-lab/builder | 청산 "최대 보유"·"트레일링" | 비활성 | `ruleDefinition`과 엔진에 `MAX_HOLD_DAYS`·`TRAILING_STOP` | P1 | ✅ |
| /quant-lab/builder, [id] | 샤프 지수 | `—` | `QuantBacktestResult.sharpe`(단순 백테스트 엔진에는 이미 있음) | P1 | ✅ |
| /backtest | 수수료·세금·슬리피지 반영 | 비활성 체크 | `BacktestService` 비용 모델 | P1 | ✅ |
| /analytics | 현재 포트폴리오 비중 비교 | 동일가중 기준 | 사용자 보유 비중으로 최적화 비교 | P1 | ✅ |
| /analytics | "분석 비중을 리밸런싱 초안으로"(구 "리밸런싱으로 보내기") | 비활성. 실거래 화면이라 연결하지 않았다 | 분석 결과 비중을 리밸런싱 **초안**으로 넘기는 흐름. 실주문 검증 선행 | P1 | ✅ 저장 안 된 초안으로만 |
| /quant-lab/market | 카드 성과(CAGR·MDD·포워드·곡선) | `—` | 마켓 목록 API에 성과 요약 포함 | P1 | ✅ |
| /quant-lab/market | 신호 이력·이번 달 신호 | 화면을 연 뒤 들어온 WS 신호만 | 구독 전략 신호 이력 API, WS를 단일 연결 다중 토픽으로 통합 | P1 | ✅ |
| /quant-lab/market | 유료 구독 | 비활성(기존과 동일) | PG 결제 연동([ADR-035](decisions/035-strategy-market-signal-access-control.md)) | P1 | 보류 — 서버가 유료 구독을 거부하도록 막음, 법무 결정 필요 ([ADR-080](decisions/080-paid-strategy-subscription-closed.md)) |
| /quant-lab/earnings | 월별 수익 차트, 이번 달, 활성 구독자, 전략별 지표 | `—`, "전략 #id" | 월 단위·전략별 집계 API(`summary.byStrategy`에 이름·지표) | P1 | ✅ (이탈률은 이력 없음) |
| /quant-lab | "운용 중"(실전 자동 운용) | 비활성 | 실전 자동 운용 상태와 실행 경로. ADR 필요, 실주문 검증 선행 | P2 | |
| /quant-lab | 상단 오늘 신호·구독 중 | `—` | 사용자별 집계 | P2 | ✅ `GET /api/quant/signals/summary` — 오늘(KST 달력일) 내 전략·구독 전략 신호 수, 구독 중인 마켓 전략 수. 실패하면 `—` ([ADR-090](decisions/090-quant-signal-alert-history-fanout.md)) |
| /quant-lab/builder | 블록 드래그앤드롭 | 클릭으로 추가 | 프론트 DnD | P2 | ✅ [552db045](https://github.com/polynomeer/monticker/commit/552db045) 네이티브 HTML5 DnD(새 의존성 없음) — 블록을 매수·매도 목록 원하는 자리에 놓기, 손잡이로 순서 변경, 키보드용 위·아래 버튼, 클릭 추가 유지 |
| /quant-lab/builder | 사이징 변동성 역가중·켈리 1/2, 최대 동시 보유 | 비활성 | 엔진 사이징 확장과 다종목 동시 보유 | P2 | |
| /quant-lab/builder | 유니버스 칩(KOSPI 200·거래대금·관리종목 제외), 일치 종목 수 | 시장·시총만 동작 | `universeJson` 확장, 일치 수 카운트 API | P2 | |
| /quant-lab/builder | 버전 탭 | 제목에 vN만 | 프론트만(`GET /rulesets/{id}/versions`가 이미 있음) | P2 | ✅ [552db045](https://github.com/polynomeer/monticker/commit/552db045) 버전 목록·읽기 전용 보기·이전 버전 대비 변경 요약, 불러오기는 기존 저장(PUT)으로만 새 버전 기록, 운용 중에는 막음 |
| /quant-lab/[id] | KOSPI 비교선, 포워드 일치율·구독자, 검증 배지 신청 | 수치만, `—`, 비활성 | 벤치마크 시계열, 구독자 조회, 배지 심사 정책 | P2 | |
| /backtest | 캔들 + 매수·매도 마커, 종목 검색 | 자산 곡선, 하드코딩 5종목 | StockChart 거래 마커, StockPicker 교체 | P2 | ✅ [125e1c48](https://github.com/polynomeer/monticker/commit/125e1c48) 기간 일봉 + 진입·청산 마커(`TradeRecord.quantity`), StockPicker 검색 |
| /analytics | 무작위 포트폴리오 산점도·최대 샤프 점, 분석 기간 | 실제 프론티어만, "보유 일봉 전체" | 표본 응답, max-Sharpe 최적화, 기간 파라미터 | P2 | |
| /quant-lab/market | 검증 배지·필터·정렬, 공유 전략 총수 | "검증 전", 비활성, "N+개" | 배지 부여 로직, total count | P2 | 부분 — 공유 전략 총수 ✅ [9bbb2135](https://github.com/polynomeer/monticker/commit/9bbb2135)·[f0c37c35](https://github.com/polynomeer/monticker/commit/f0c37c35) `GET /api/quant/market/count`(목록과 같은 FROM·JOIN). 검증 배지는 심사 정책 결정 필요 |
| /quant-lab/earnings | 평균 별점, 다음 정산일 | `—` | 리뷰·별점, 정산 일정 | P2 | |

## 5. 실전투자

| 화면 | 항목 | 지금 | 필요한 것 | 우선순위 | 상태 |
|---|---|---|---|---|---|
| /brokerage/orders | 상단 "등락" | `—` | `/api/stocks/{id}/price`에 전일 종가·등락률 | P1 | ✅ |
| /brokerage/orders | 호가 단위·현재가 ±30% 검증 | 클라이언트에서 계산(국내 6자리 종목). 막지 않는 '참고' 항목 | **서버** 호가 단위·가격제한폭 검증(400 거부)과 전일 종가 데이터 | P1 | ✅ [ADR-081](decisions/081-krx-limit-order-price-validation.md) |
| /brokerage/orders | 정정 | "주문 취소"만 동작 | 정정 API(KIS/Toss), 결과 불명 처리 포함 | P1 | 보류 — KIS 정정 후 수명주기·Toss 정정 API 규격 확인 필요, 실주문 코드 없음 |
| /brokerage | API 지연시간 | tokenValid로 "정상/재인증 필요"만 | 계좌 상태 응답에 latency·lastError | P2 | |
| /brokerage | 보유·주문 CSV 내보내기 | 없음 | 프론트 | P2 | ✅ 화면에 보이는 보유·주문·정산 표(/brokerage, /brokerage/orders). 계좌번호 마스킹, 키·토큰 없음, 수식 주입 방지, BOM, KST 날짜 파일명 — [exportCsv.ts](../apps/web/src/components/brokerage/exportCsv.ts) |
| /brokerage/connect | 4단계 스테퍼, 권한(scope) 확인 | 한 화면 폼 + 진행 표시. connect 토큰 발급으로만 확인 | 권한 확인 API | P2 | |
| /brokerage/orders | 예상 수수료 | "증권사 기준" | 증권사별 수수료율 | P2 | |
| /brokerage/conditional-orders | 유효 기간 선택 | "90일(자동 만료)" 고정 | 요청에 유효일수, 상한 검증 | P2 | |
| /brokerage/conditional-orders | 통계·발동 기록·현재가 | 현재 페이지 데이터로 계산, 종목별 시세 개별 호출 | 상태별 집계, 발동 이벤트 로그, 일괄 시세 | P2 | |
| /brokerage/rebalance | 예상 거래비용, 신규 매수 예상 금액 | `—` | preview 응답에 estimatedCost·leg 가격 | P2 | |
| /brokerage/rebalance | 주문 건별 확인 | 일괄 실행 전 한 번 확인 | 건별 승인은 실행 사가 변경 필요 | P2 | |

## 6. 계정 · 설정

| 화면 | 항목 | 지금 | 필요한 것 | 우선순위 | 상태 |
|---|---|---|---|---|---|
| /signup | [선택] 마케팅 수신 동의 | 동의는 저장된다(ADR-068). 철회는 `DELETE /api/users/me/consents/MARKETING` | 설정 화면에 철회 토글, 실제 마케팅 발송 경로가 최신 동의를 확인 | P1 | ✅ 설정 화면 토글 |
| /subscription | 다음 결제일 | 만료일(`expiresAt`)로 표시 | 정기결제 스케줄(`next_billing_at`) | P1 | ✅ [ADR-083](decisions/083-next-billing-date-derived-from-renewal-schedule.md) |
| /subscription | FAQ 답변(플랜별 기능 범위·환불) | 사실에 맞게 고쳤고, 환불은 "[법률 검토 후 확정]" | 플랜별 기능 게이팅 정책, 환불 정책 | P1 | 보류 — 플랜별 기능 제한 정책(사업 결정)·환불 정책(법무) 필요 |
| /settings/notifications | 전체 알림, 거래량 급증, 퀀트 시그널, 체결·정산, 전략 마켓 소식 | 비활성 토글 | `NotificationPreferenceRequest` 필드 추가와 발송 경로 확인(리스크·결과 확인 중은 0-5) | P1 | ✅ [ADR-082](decisions/082-notification-preferences-enforced-at-delivery.md) |
| /login | 로그인 상태 유지 | 비활성 | refresh 토큰 수명 2단계 | P2 | |
| /onboarding | 관심 분야·사용 방식 저장 | 선택만 되고 저장 안 함 | `PUT /api/users/me/preferences`, 홈·알림 우선순위 반영 | P2 | ✅ 저장·조회(V86, 화이트리스트) [ADR-089](decisions/089-paper-initial-capital-and-user-preferences.md). 홈·알림 우선순위 반영은 후속 |
| /onboarding | 시작 자금 3,000만원·1억원 | 비활성(1,000만원 고정) | 모의 계좌 생성 시 초기 자금 파라미터 | P2 | ✅ `POST /api/paper/account` 처음 생성 때만, 화이트리스트 3종, 대사·초기화가 계좌별 시작 자금 사용 [ADR-089](decisions/089-paper-initial-capital-and-user-preferences.md) |
| /onboarding | 3단계 관심종목 고르기 | 검색 화면 링크 | 인기 종목 추천 + 일괄 추가(기존 watchlist API) | P2 | ✅ 거래대금 상위 12종목(라벨로 밝힘) + 다중 선택 담기. 일괄 API가 없어 단건 순차·부분 실패 보고·이미 담긴 종목 생략 — [watchlistBulk.ts](../apps/web/src/lib/watchlistBulk.ts) |
| /subscription | 연간 결제, 카드 변경 | 연간 비활성. 카드는 등록/해지만 | 연간 요금제, 빌링키 교체 API | P2 | |
| /settings/notifications | 카카오 알림톡, 방해 금지 시간 | 비활성 | 비즈메시지 연동, quiet-hours 필드와 발송 필터 | P2 | 부분 ✅ 방해 금지 시간 [ADR-093](decisions/093-notification-quiet-hours-and-delivery-channels.md) — V90, KST·자정 넘김, 끌 수 있는 알림의 푸시만 보내지 않음(미루지 않음), 이메일·이력은 그대로, 리스크·‘결과 확인 중’·조건부 주문 실패는 즉시. 카카오 알림톡은 외부 연동 필요 — 비활성 유지 |
| /login | `?error=oauth2` 메시지 | 표시 안 함(이전부터) | 쿼리 오류 메시지 표시와 테스트 목 확장 | P2 | ✅ 아는 코드만 고정 문구(원문 미표시), `useSearchParams` 목 추가 — [loginError.ts](../apps/web/src/lib/loginError.ts) |

---

## 시안과 일부러 다르게 둔 것

시안을 그대로 따르면 동작이 거짓이 되거나 기존 기능이 사라지는 곳은 다르게 두었다.

- **비밀번호 규칙 문구**는 실제 검증(8자 이상, 영문·숫자)을 따른다. 시안의 "12자, 기호"가 아니다.
- **소셜 로그인 버튼**은 백엔드가 지원하는 3개(Google·카카오·네이버)를 모두 둔다.
- **스크리너**는 `/`에서 `/screener`로 옮겼다. `/`는 시안의 마켓 홈이다.
- **기존 기능은 시안에 자리가 없어도 탭으로 남겼다.**
  - 종목 화면: 뉴스, 요약, 투자자 동향, 토론, 가격 알림
  - 분석: 차트 패턴·시장 국면
  - 수익: 수익·출금 내역
- **실전 주문**은 시안의 확인 단계를 실제로 구현했다.
  - 확인하는 순간의 주문 내용을 스냅샷으로 고정하고, 필드를 바꾸면 무효가 된다. 전송한 뒤에는 스냅샷을 비운다.
  - "10초 뒤 결과 확인 중" 문구는 실제 동작("자동 재전송하지 않음", ADR-056)으로 바꿨다.
- **사실이 아닌 시안 문구는 넣지 않았다.**
  - 리스크 "24시간 뒤 적용"
  - 출금 "3영업일·원천징수"
  - 연동 "출금·이체 불가능" 단정(→ "monticker를 통해서는 출금·이체를 할 수 없습니다")
