# 해외 주식앱 탭은 "계좌 먼저, AI는 덧씌우기"로 수렴한다

> 2026-10-18 조사 · "이전 보고서" = [stock-app-menu-comparison.md](stock-app-menu-comparison.md) · 근거 노트: [notes/overseas-app-menu-recheck/](notes/overseas-app-menu-recheck/)

2026년 10월 18일 기준으로 다시 확인해 보니, 해외 주식앱 30여 개 가운데 **하단 탭 전체를 공식 출처가 순서까지 밝힌 곳은 moomoo와 Yahoo Finance 두 곳뿐**이다. 나머지는 탭 이름 일부만 공식 도움말·릴리스 노트로 확인되거나(Robinhood, Fidelity, Vanguard, IBKR GlobalTrader, Webull, Trading 212 등), 제3자 리뷰에만 근거하거나, 아예 확인되지 않는다. 그래도 확인된 조각을 모으면 업계 흐름은 분명하다. 첫 화면은 **계좌·보유 자산이나 관심종목**이 차지한다. 주문은 탭이 아니라 **종목 화면의 버튼이나 티켓**으로 들어간다. 모의투자는 탭이 아니라 **계좌 모드 전환**이다(Trading 212 "Switch to practice", eToro Real/Virtual, Power E*TRADE 로그인 화면 Paper 탭). 2025~2026년의 AI 기능("왜 움직였나")은 새 탭을 만들지 않고 **종목 상세·포트폴리오 화면 위에 얹힌다**(Robinhood Cortex Digests, Public Key Moments, eToro Tori). 이 재검증으로 이전 보고서의 판단 몇 가지가 바뀌었다. IBKR GT의 Home 탭은 사라진 것으로 정리됐고, Webull 모의투자는 Markets 아래로 확인됐으며, moomoo는 고정 6탭이 아니라 사용자 편집 Dock(고정 5개)로 바뀐 정황이 나왔다. Robinhood는 2026년 9월 **Predict 탭**을 발표했다. Apple 쪽에서는 검색 탭 역할, 스크롤 시 탭 바 최소화, 하단 액세서리(`tabViewBottomAccessory`, `isEnabled:` 변형은 iOS 26.1+)가 **1차 출처로 확인**됐다. 반면 iOS 27에서 검색 탭을 다시 인라인으로 넣는다는 이야기와 `.prominent`의 실제 동작은 여전히 2차 출처뿐이다. monticker의 4탭안(피드·관심종목·내 계좌·검색)은 이 기준에 맞는다. F안(하단 액세서리 계좌 바)은 "항상 떠 있는 잔고 바"가 아니라 **모의/실전 모드와 진행 중인 상태를 보여 주는 조건부 상태 바**로 좁힐 때 근거가 가장 단단하다.

## 검증 등급은 출처의 종류와 날짜로 매겼다

이 보고서의 모든 탭 주장에는 세 등급 중 하나를 붙였다. **확인**은 공식 도움말, 릴리스 노트, App Store "새로운 기능", 공식 보도자료가 그 라벨을 하단 탭으로 직접 밝힌 경우다. **부분 확인**은 공식 출처가 일부 탭만 밝히거나 날짜가 없거나 오래된 경우, 혹은 날짜가 있는 제3자 리뷰에만 근거한 경우다. **미확인**은 인용할 출처를 찾지 못했거나 출처끼리 충돌하는 경우다. 미확인 항목에 대해서는 탭 이름을 사실처럼 쓰지 않았다. 순서는 따로 다뤘다. 탭 이름이 공식으로 확인돼도 좌우 순서는 대개 미확인이다.

한계도 분명히 밝혀 둔다. 모든 조사는 2026-10-18에 웹 검색과 페이지 열람으로만 했다. 실제 기기에서 앱을 열어 보지 않았고, App Store 스크린샷은 도구로 읽을 수 없었다. 공식 도움말 상당수는 날짜가 없고 "© 2026" 꼬리말만 있다. 이런 경우 "날짜 없음, 2026-10-18 열람"으로 표시했다. Schwab, Revolut, Bitpanda, BrokerChooser, 일부 IBKR·Monex 페이지는 접근이 막혀(HTTP 403/404) 검색 스니펫으로만 확인했고, 이 점도 표에 적었다. App Store 릴리스 노트가 월·일만 보여 주는 경우 연도는 버전 순서로 추정했다.

## 미국 네오브로커는 "상품 영역 탭"과 슈퍼앱 안의 한 칸으로 나뉜다

Robinhood는 이번 재검증에서 가장 많이 바뀐 앱이다. 이전 보고서는 2018년 발표 이후 공식 자료가 없다고 보고 미확인으로 처리했다. 이번에는 공식 도움말이 **"Investment or Crypto tab"**, **"Account (person icon) → Menu (3 bars)"** 경로를 쓰는 것을 확인했다 ([Robinhood Earn 도움말](https://robinhood.com/us/en/support/articles/crypto-earn/), 날짜 없음). 공식 @AskRobinhood 계정도 2025년 12월에 같은 경로를 안내했다 ([X](https://x.com/AskRobinhood/status/1999166314183594430?lang=en)). 옵션은 별도 탭이 아니라 "Investing → Options" 안에 있다 ([Investing tools](https://robinhood.com/us/en/support/articles/investing-tools/)). IRA는 탭이 아니라 계좌 선택으로 들어간다 ([Retirement](https://robinhood.com/us/en/support/articles/retirement-investing/)). 2026년 9월 29일 HOOD Summit에서는 **"a new Predict tab and prediction-market hub"**가 발표됐다 ([Yahoo Finance/MarketBeat, 2026-09-30](https://finance.yahoo.com/markets/stocks/articles/robinhood-markets-unveils-24-7-020205692.html)). 다만 이 발언은 언론 보도로만 확인했고 Robinhood 뉴스룸 원문은 찾지 못했다. 모든 사용자에게 배포됐는지도 확인하지 못했다. 한편 제휴 리뷰 TheLines(2026-09-22)는 하단 바를 "Menu, Search, Markets, Account, Cash"로 적었다 ([TheLines](https://www.thelines.com/prediction-markets/robinhood/app/)). 그러나 공식 경로상 Menu는 Account 안에 있으므로 신뢰하기 어렵다. 결론적으로 Robinhood는 Watchlist/Markets/Trade 같은 고전적 탭이 아니라 **상품 영역별 탭(Investing, Crypto, Predict)에 Search와 Account를 더한 구조**로 보인다. 다만 이는 추론이고 순서는 미확인이다.

| 앱 | 확인된 탭/진입점 | 등급 | 출처·날짜 |
|---|---|---|---|
| Robinhood | Investing(Investment) · Crypto · Account(사람 아이콘, 안에 ≡ Menu) | **확인**(이름만), 순서 미확인 | [공식 도움말](https://robinhood.com/us/en/support/articles/crypto-earn/) 날짜 없음; [@AskRobinhood 2025-12](https://x.com/AskRobinhood/status/1999166314183594430?lang=en) |
| Robinhood | Predict 탭(예측시장 허브) | **부분 확인**(경영진 발표를 언론이 전함, 배포 여부 미확인) | [Yahoo/MarketBeat 2026-09-30](https://finance.yahoo.com/markets/stocks/articles/robinhood-markets-unveils-24-7-020205692.html) |
| Robinhood | Search(하위 탭 now·upcoming·macro·crypto·sports) | **부분 확인**(제3자 실사용 리뷰) | [StockBrokers.com 2026-10-01](https://www.stockbrokers.com/review/robinhood) |
| Robinhood | "Menu, Search, Markets, Account, Cash" | **미확인**(공식 경로와 충돌) | [TheLines 2026-09-22](https://www.thelines.com/prediction-markets/robinhood/app/) |
| Public | 포트폴리오 화면 좌상단 ≡ 메뉴(하단 탭 아님) | **확인**(공식, 날짜 없음) | [Public 도움말](https://help.public.com/en/articles/4375182-where-do-i-find-my-account-number) |
| Public | Markets 탭 | **부분 확인**(제3자 리뷰) | [StockBrokers.com 2026-09-18](https://www.stockbrokers.com/review/public-com) |
| SoFi | 하단 "Invest" 아이콘/탭 | **확인**(공식, 날짜 없음, 스니펫) | [SoFi 지원](https://support.sofi.com/hc/en-us/articles/360044854191-How-do-I-buy-and-sell-a-stock-on-the-SoFi-app) |
| Cash App | 좌하단 Money 탭 → Stocks·Bitcoin 타일(투자는 탭 아님) | **확인**(공식, 날짜 없음; X 2025-11) | [Cash App 도움말](https://cash.app/help/6539-view-cash-balance); [Cash App X](https://x.com/CashApp/status/1990449889952903354) |

SoFi와 Cash App은 투자가 슈퍼앱의 한 칸이라는 점에서 국내 토스·네이버페이와 닮았다. SoFi는 하단 "Invest" 탭 하나에 투자를 몰았다. Cash App은 한 단계 더 깊어서, 투자가 Money 탭 안의 **타일**이다. 다만 Cash App의 오래된 도움말에는 여전히 "Investing tab"이라는 표현이 남아 있어 도움말끼리 일관되지 않다 ([Cash App 도움말](https://cash.app/help/us/6485)). Public 도움말은 하단 탭 이름을 하나도 밝히지 않는다. Public은 모의투자를 제공하지 않는다 ([StockBrokers.com 2026-09-18](https://www.stockbrokers.com/review/public-com)). 네 앱 모두 Liquid Glass 도입 근거는 찾지 못했다. Robinhood의 2026년 디자인 소식은 폴더블·아이패드용 "Dual Display"(2026-10-23 예정)뿐이다 ([Yahoo/MarketBeat](https://finance.yahoo.com/markets/stocks/articles/robinhood-markets-unveils-24-7-020205692.html)).

## 미국 기존 증권사는 계좌를 첫 탭에, 전문 기능은 모드로 나눈다

기존 증권사는 대부분 첫 탭이 계좌·자산이고 마지막 탭이 Menu/More/Profile이다. 이번에 가장 크게 보강된 곳은 Fidelity다. 이전 보고서에서 Standard 모드는 "경로명으로 추정"이었다. 이번에는 제3자 리뷰가 **Home · Investing · Transact · Planning · Discover** 5개 버튼을 이 순서로 적었고 ([StockBrokers.com 2026-09-30](https://www.stockbrokers.com/review/fidelityinvestments)), Fidelity 공식 릴리스 노트가 같은 이름을 최상위 목적지로 쓴다("Investing > Positions", "Transact > Activity & Orders", "Discover > Collections"; [Fidelity What's New](https://fidelity.com/mobile/whats-new-fidelity-app), 2025-09~2026-05). Trader+ 모드는 2025년 9월에 "Portfolio, Watchlist, Markets now in the bottom nav"를 공식 발표했다. 같은 콘텐츠가 모드에 따라 이름만 바뀐다. 보유 종목이 Standard에서는 "Investing > Positions", Trader+에서는 "Portfolio > Positions"다(2026-05). **하나의 앱 안에 간편/전문 정보 구조를 두는 직접적인 선례**다. Fidelity는 모의투자를 제공하지 않는다(같은 리뷰).

IBKR GlobalTrader(GT)는 이전 보고서의 충돌이 풀렸다. 2025년 iOS 릴리스 노트(2025-10-14 갱신)는 "Forecasts / Watchlist / Portfolio / Explore tab at the bottom"이라고만 쓰고 Home은 한 번도 언급하지 않는다 ([GT iOS 2025](https://ibkrguides.com/releasenotes/gt-ios-2025.htm)). Home을 포함한 목록은 날짜 없는 Campus 강의에서 나왔는데, 같은 문구가 IMPACT 앱 강의에도 그대로 있다. 그래서 오래된 디자인 계보를 반영한 것으로 판단했다 ([IBKR Campus](https://www.interactivebrokers.com/campus/trading-lessons/ibkrs-impact-app/)). GT에서 Trade는 Portfolio 안의 상단 탭이다. 2025년 12월 개편(AI 시장 인사이트, 예측 계약 통합)의 탭 수준 변화는 확인하지 못했다 ([fintech.global 2026-01-02](https://fintech.global/2026/01/02/interactive-brokers-unveils-smarter-ibkr-globaltrader-mobile-trading/)). 주력 앱 IBKR Mobile은 연도마다 자료가 다르다. 2024년 공식 자료가 Home·Portfolio·Trade·Watchlist·Markets 다섯 화면과 6번째 Transactions 탭 추가를 암시하지만 ([IBKR Mobile iOS 2024](https://www.ibkrguides.com/releasenotes/ibkrmobile/mobile-ios-2024.htm); [Android 2024](https://www.ibkrguides.com/releasenotes/ibkrmobile/mobile-android-2024.htm)), 2025~2026년 기준 기본 탭은 미확인이다.

Webull도 이전 보고서의 2024년 싱가포르 리뷰("Open Account" 탭)를 공식 도움말로 보정했다. 공식 도움말은 "Tap Account (bottom center)", "Tap the Feeds icon at the bottom"이라고 쓰고, 모의투자는 **Markets 탭 → paperTrade**로 안내한다 ([주문 FAQ](https://www.webull.com/help/faq/291-Where-do-I-place-an-order); [시작 가이드](https://www.webull.com/help/faq/11032-Getting-Started); [모의투자 FAQ](https://www.webull.com/help/faq/11069-Paper-Trading), 모두 날짜 없음). Vanguard는 App Store 릴리스 노트로 Portfolio·Transact·Profile·Learn 탭 이름이 확인되고, 2026년 5월 "The Research tab is now Explore!"로 탭 이름을 바꿨다 ([Vanguard App Store](https://apps.apple.com/us/app/vanguard-save-invest-retire/id335186209), 2026 연도 추정). Vanguard Explore, IBKR GT Explore, Fidelity Discover처럼 **탐색 탭 이름이 "Explore/Discover"로 모이는 흐름**이 보인다.

| 앱 | 확인된 탭 | 등급 | 출처·날짜 |
|---|---|---|---|
| Fidelity (Standard) | Home · Investing · Transact · Planning · Discover | 이름 **확인**(공식 릴리스 노트), 순서 **부분 확인**(제3자) | [StockBrokers 2026-09-30](https://www.stockbrokers.com/review/fidelityinvestments); [What's New](https://fidelity.com/mobile/whats-new-fidelity-app) |
| Fidelity (Trader+) | Portfolio · Watchlist · Markets (나머지 미확인) | **확인**(3개만) | [What's New 2025-09](https://fidelity.com/mobile/whats-new-fidelity-app) |
| Fidelity | Profile은 탭이 아닌 헤더 진입점으로 추정 | **미확인** | 같은 출처 |
| Schwab Mobile | — | **미확인**(2025~2026 자료 없음) | [App Store](https://apps.apple.com/gb/app/schwab-mobile/id407358186) |
| thinkorswim | Overview · Watchlist · Trade · Positions · More (사용자 편집 가능) | **부분 확인**(공식, 날짜 없음; 이번엔 403으로 재열람 실패) | [Schwab](https://www.schwab.com/learn/story/9-steps-to-master-thinkorswim-mobile-iphone) |
| E*TRADE | Accounts · Watch Lists · Trade · Markets · Menu | **부분 확인**(제3자) | [Brokerage-Review 2026-09-22](https://www.brokerage-review.com/online-trading/mobile/etrade-app.aspx) |
| Power E*TRADE | Dashboard · Portfolio · Quote · Trade · Menu | **부분 확인**; Menu 탭은 **확인** | 같은 리뷰; [App Store v5.6, 2025-08-13](https://apps.apple.com/us/app/power-e-trade-advanced-trading/id1111881020) |
| IBKR GT | Forecasts · Watchlist · Portfolio · Explore (Home 없음) | **확인**(이름), 순서 미확인 | [GT iOS 2025, 2025-10-14](https://ibkrguides.com/releasenotes/gt-ios-2025.htm) |
| IBKR Mobile | Home · Portfolio · Trade · Watchlist · Markets (+선택형 Transactions) | **부분 확인**(공식 2024) | [iOS 2024](https://www.ibkrguides.com/releasenotes/ibkrmobile/mobile-ios-2024.htm) |
| Vanguard | Portfolio · Transact · Explore(구 Research) · Profile · Learn | 이름 **확인**, 순서·Learn 존속 미확인 | [App Store 2025-10~2026-07](https://apps.apple.com/us/app/vanguard-save-invest-retire/id335186209) |
| Webull (US) | Account(하단 중앙) · Feeds · Markets · Watchlists · Menu | Account 중앙·Feeds **확인**, 전체 순서 **부분 확인** | [Webull 도움말](https://www.webull.com/help/faq/291-Where-do-I-place-an-order) 날짜 없음 |

## 유럽·소셜 앱은 미니멀, 콤팩트 탭, 슈퍼앱 세 갈래로 갈린다

moomoo 미국판 공식 가이드는 **Watchlist(기본) · Markets · Accounts · News · Discover · Me** 6탭을 순서대로 밝힌다. Discover 안에는 Moo 커뮤니티, Learn, Papertrade 하위 탭이 있다 ([moomoo US overview](https://www.moomoo.com/us/learn/overview), © 2026). 호주 가이드도 같은 순서다 ([moomoo AU](https://www.moomoo.com/au/learn/overview)). 새로 확인된 것은 미국 매뉴얼의 **Dock**이다. 하단 고정 영역에 "최대 5개 탭"을 두고, 최근 쓴 탭이 임시 영역에 뜨며, "Discover 탭은 Dock에서 제거할 수 없다" ([moomoo 매뉴얼](https://www.moomooapp.com/us/manual/topic-14-20), 날짜 없음). 고정 6탭과 고정 5탭+임시 영역이 공식 문서끼리 충돌한다. 새 빌드가 Dock 방식으로 바뀌었을 가능성이 높지만 확정할 수 없다.

Trading 212는 공식 도움말 두 건으로 **Home · Portfolio · Card**와 오른쪽 끝 **Menu(현재 "☰ More")**, 그리고 이름이 확인되지 않은 "중앙 아이콘"이 드러난다 ([계정 전환](https://helpcentre.trading212.com/hc/en-us/articles/8614634278685-How-do-I-switch-between-my-accounts); [More 탭](https://helpcentre.trading212.com/hc/en-us/articles/38653258622493-Introducing-the-new-More-tab-design), 둘 다 날짜 없음). More 탭에는 **Switch to practice**, Learn(커뮤니티 포럼 포함), Market tools, Support가 들어 있다. 2025년 12월 개편 철회도 정확히 확인했다. 2025-12-24에 직원이 1월에 이전 레이아웃을 되돌리겠다고 공지했고 ([T212 커뮤니티](https://community.trading212.com/t/portfolio-redesign-update/89501)), 2026-01-12에 웹에 반영되고 모바일 v8.14.1로 배포가 시작됐다 ([T212 커뮤니티](https://community.trading212.com/t/the-old-new-portfolio-look-is-now-live/89758)). 철회는 부분적이었다. 차트 Y축 값과 파이/개별 종목 필터는 2026년 4월까지도 사용자들이 계속 요구했다. 이 개편은 **탭 바가 아니라 Portfolio 화면**을 바꾼 것이었다.

eToro의 새 앱은 실제로 배포 중이다. 2026-07-07 발표 후 별도 "etoro AI" 앱으로 얼리 액세스를 열었고, 2026-10-04부터 점진 이전, App Store v700.x는 10-06부터 올라왔다 ([eToro 2026-09-22](https://www.etoro.com/news-and-analysis/etoro-updates/your-etoro-app-is-changing-your-account-isnt/); [App Store GB](https://apps.apple.com/gb/app/etoro-investing-made-social/id674984916)). 공식 자료가 밝힌 정보 구조 변화는 Portfolio와 Watchlist의 "advanced mode"(기본은 간단, 원하면 고밀도), "fewer taps to trade", 그리고 먼저 말을 거는 AI 에이전트 Tori다. 하단 탭 구성은 어떤 1차 자료에도 나오지 않는다. 리뷰에 따르면 Tori는 탭이 아니라 "핵심 화면 위의 아이콘"이다 ([Smart Money with Kai 2026-07-23](https://smartmoneywithkai.com/blog/etoro-new-ai-app)). 데모 전환은 웹에서 사용자 이름 아래 Real/Virtual 버튼이다 ([eToro 데모](https://etoro.com/trading/demo-account)). 새 모바일 앱의 위치는 미확인이다.

| 앱 | 확인된 탭/구조 | 등급 | 출처·날짜 |
|---|---|---|---|
| moomoo (US/AU) | Watchlist · Markets · Accounts · News · Discover · Me | **확인**(공식, © 2026) | [US](https://www.moomoo.com/us/learn/overview); [AU](https://www.moomoo.com/au/learn/overview) |
| moomoo (US) | Dock: 고정 최대 5탭 + 임시 영역, Discover 제거 불가 | **확인**(공식, 날짜 없음), 6탭 문서와 충돌 | [매뉴얼](https://www.moomooapp.com/us/manual/topic-14-20) |
| Trading 212 | Home · Portfolio · [중앙 아이콘] · Card · ☰ More | **부분 확인**(공식, 날짜 없음; 위치·중앙 라벨은 추론) | [도움말](https://helpcentre.trading212.com/hc/en-us/articles/38653258622493-Introducing-the-new-More-tab-design) |
| Revolut | 하단 메뉴 "Invest"(그 안에서 스크롤해 Watchlist·Learn) | **부분 확인**(공식 도움말·블로그 스니펫, 403) | [Revolut Help](https://help.revolut.com/en-CZ/help/wealth/stocks/getting-started-with-trading/managing-your-trading-account/trading-alerts/managing-my-trading-watchlist-and-price-alerts/) |
| Freetrade | Activity 탭; Portfolio 탭(관심종목 포함); Discover 탭 | Activity **확인**(2025-07-11), 나머지 **부분 확인**(2019·2022) | [Freetrade Help](https://help.freetrade.io/en/articles/6416973-how-can-i-see-my-monthly-statement); [블로그 2019](https://freetrade.io/blog/watchlist-has-arrived-on-your-freetrade-app) |
| Scalable Capital | 하단 "Finden", 오른쪽 아래 Profile | **부분 확인**(제3자 2026-06-08; 공식 2021·2022 개편 공지) | [Zendepot](https://zendepot.de/depot/scalable-capital-anleitung); [Scalable 2021](https://de.scalable.capital/en/product-news/app-new-look) |
| Trade Republic | Wealth · Cash 두 섹션, 좌상단 아바타, 하단 떠 있는 Search·Transfer 버튼 | Wealth/Cash **확인**(공식 스니펫), 나머지 **부분 확인** | [TR Support](https://support.traderepublic.com/en-sk/1602-What-has-changed-in-the-new-app-design-); [Zendepot 2026-06-23](https://zendepot.de/depot/trade-republic-anleitung) |
| eToro (새 앱) | 탭 바 미상; Tori는 화면 위 아이콘 | **미확인** | [App Store GB 2026-10](https://apps.apple.com/gb/app/etoro-investing-made-social/id674984916) |
| Stocktwits | Home(관심종목+트렌딩) · Explore · Trending · 알림 벨 | **부분 확인**(공식 스니펫, 2026-08-24, 순서 미상) | [Stocktwits Help](https://help.stocktwits.com/c/navigating/articles/using-the-navigation-bar-on-apps) |
| Bitpanda | — | **미확인**(403) | [Bitpanda Help](https://support.bitpanda.com/hc/en-gb/sections/115001027285-About-Bitpanda) |

세 갈래를 정리하면 이렇다. Trade Republic은 고전적 탭 바 없이 Wealth|Cash 섹션 전환, 아바타 메뉴, 화면별 떠 있는 버튼(Transfer, 종목 화면의 "Handeln", "Sparplan")으로 버틴다. Trading 212·Scalable·Freetrade는 포트폴리오 우선 4~5탭에 검색/발견 칸과 오른쪽 끝 프로필/More를 둔다. Revolut은 은행 앱 안의 "Invest" 한 칸이다.

## 아시아·캐나다는 앱 묶음, 스와이프 면, 커뮤니티로 차별화한다

아시아 쪽은 확인률이 가장 낮다. 일본 증권사 공식 가이드는 하단 탭 바가 아니라 「メニュー」 화면만 설명한다. 그래서 SBI 株アプリ, SBI 米国株アプリ, iSPEED, マネックス, 三菱UFJ eスマート, PayPay証券의 하단 탭은 **전부 미확인**이다. 대신 구조적 특징은 확인된다. SBI는 2026년 봄 출시 예정인 **SBI証券 Plus**를 허브로 두고, 메뉴의 「アプリ一覧」에서 국내주식·미국주식·투신 앱을 띄운다 ([SBI 보도자료 2025-12-17](https://search.sbisec.co.jp/v2/popwin/info/home/irpress/prestory251217_011500.pdf); [SBI Plus 메뉴 가이드](https://search.sbisec.co.jp/v2/popwin/guide/tool/sbi_app/02_basic/menu.html)). 탐색 단위가 탭 바가 아니라 **앱 묶음**인 셈이다. 楽天 iSPEED는 최대 10페이지까지 부품 30여 개로 꾸미는 My Page를 첫 화면으로 두고 ([App Store JP](https://apps.apple.com/JP/app/id389339704)), 2025-11-29 호가창 주문 「武蔵」와 2025-12-21 iPad 3분할로 전문가 방향을 강화했다 ([楽天 2025-11-21](https://www.rakuten-sec.co.jp/web/company/newsrelease/pdf/press20251121.pdf); [楽天 2025-12-19](https://www.rakuten-sec.co.jp/web/company/newsrelease/pdf/press20251219.pdf)). マネックス는 2020년 공지로 「資産」 아이콘과 즐겨찾기 12개를 담는 「メニュー」 아이콘만 확인된다 ([Monex 2020-05-08](https://info.monex.co.jp/news/2020/20200508_01.html)).

Wealthsimple은 독특한 구조가 공식으로 확인됐다. 2025-08-18 개편 공지에 따르면 **Home을 중심으로 왼쪽으로 밀면 Spend, 오른쪽으로 밀면 Invest**가 나오고, 하단에는 송금(두 화살표), 활동(시계), Discover(돋보기) 아이콘이 있다. 하단 진입점은 "그대로 유지했다"고 밝혔다 ([Wealthsimple 2025-08-18](https://product-news.wealthsimple.com/meet-our-re-designed-wealthsimple-app-appui)). Tiger Trade는 2026년 9월 릴리스 노트에 Profile 탭, Portfolio, Watchlist, News, Trade Feed, Leaderboard가 나오지만 전체 탭 바는 알 수 없다 ([App Store SG](https://apps.apple.com/sg/app/tiger-trade-invest-globally/id1023600494)). 富途牛牛는 App Store 제목부터 "投資社區 AI投資分析工具"를 내세우고, 커뮤니티 牛牛圈 이용자 3,100만 명 이상을 표방하며, 資訊·社區·投教에 AI 진입점을 넣었다 ([App Store](https://apps.apple.com/us/app/id6752377955)). Stake는 "Watch 탭"이 공식으로 확인되고, 외부 AI 에이전트가 관심종목을 만들 수 있는 MCP 서버를 운영한다 ([Stake 위젯](https://hellostake.com/au/support/products-and-features/widget/49156454353945); [Stake MCP](https://hellostake.com/au/support/products-and-features/mcp-server/56913247594905)).

| 앱 | 확인된 탭/구조 | 등급 | 출처·날짜 |
|---|---|---|---|
| Wealthsimple | Home ↔ Spend / Invest 스와이프 면 + 하단 송금·활동·Discover 아이콘 | 구조 **확인**(2025-08-18), 아이콘 라벨 **부분 확인** | [Wealthsimple](https://product-news.wealthsimple.com/meet-our-re-designed-wealthsimple-app-appui) |
| Tiger Trade | Profile 탭, Portfolio, Watchlist, News(전체 바 미상) | **부분 확인**(2026-09 릴리스 노트) | [App Store SG](https://apps.apple.com/sg/app/tiger-trade-invest-globally/id1023600494) |
| 富途牛牛 | 【我的】 영역, 資訊·社區·投教 섹션 | **부분 확인**(App Store, 연도 미표기) | [App Store](https://apps.apple.com/us/app/id6752377955) |
| Stake (AU) | Watch 탭, 미국/호주 시장 토글 | **부분 확인**(공식, 1개 탭) | [Stake 지원](https://hellostake.com/au/support/products-and-features/widget/49156454353945) |
| SBI証券 (株·米国株·Plus) | 「メニュー」 화면만 공식 확인, 하단 탭 미상 | 탭 **미확인** | [SBI 가이드](https://search.sbisec.co.jp/v2/popwin/guide/tool/sbi_app/02_basic/menu.html) |
| 楽天 iSPEED | My Page 첫 화면; 하단 「検索」·ニュース는 비공식 블로그만 | 탭 **미확인** | [App Store JP](https://apps.apple.com/JP/app/id389339704) |
| マネックス | 「資産」·「メニュー」 아이콘 | **부분 확인**(공식 2020) | [Monex](https://info.monex.co.jp/news/2020/20200508_01.html) |
| PayPay証券, 三菱UFJ eスマート | — | **미확인** | — |

## 정보·퀀트 앱에서는 검색이 탭이 되고 전략 제작은 웹에 남는다

Yahoo Finance는 이 범주에서 유일하게 공식 도움말이 하단 5탭을 밝힌다. **Home · News · Search · Markets · Account** 순서다. 포트폴리오와 관심종목은 Home에, 알림 설정은 Account에, 스크리너는 Search에 있다 ([Yahoo Help SLN22069](https://help.yahoo.com/kb/SLN22069.html), 날짜 없음). "Home, My Portfolio, Markets, News, Search"라는 제3자 목록은 공식 자료와 충돌하므로 버렸다. 2026년 Yahoo는 탭 구성 대신 Quote 화면 차트 아래 **실시간 실적 발표 오디오**를 넣었다 ([App Store](https://apps.apple.com/us/app/yahoo-finance-stock-market/id328412701), 2026-03~04 연도 추정). 이벤트를 차트 위 시간축에 붙인 사례다.

TradingView는 App Store 릴리스 노트(2.140~2.142, 2026-09~10 추정)로 **Menu** 안에 Notifications 화면이 생긴 것만 확인된다 ([App Store](https://apps.apple.com/us/app/tradingview-track-all-markets/id1205990992)). Watchlist · Chart · Ideas · News · Profile 목록은 2025년 5월 브로커 영상 하나에 근거하므로 미확인이다 ([Optimus Futures](https://optimusfutures.com/blog/master-the-tradingview-mobile-dashboard-in-seconds-tradingview-mobiletrading-trading/)). 주문과 모의투자는 모두 **차트 안**에 있다. 모의투자는 차트 하단 Trading Panel에서 "Paper Trading" 브로커를 연결하는 방식이다 ([TradingView 지원](https://www.tradingview.com/support/solutions/43000479975-how-to-connect-or-disconnect-a-paper-trading-account/)). Seeking Alpha 도움말은 여전히 좌상단 햄버거 사이드 메뉴와 "left nav bar"를 설명한다 ([SA Help](https://help.seekingalpha.com/iphone-app/where-can-i-find-my-investing-group-subscriptions-on-the-iphone-ipad-app)). 그러나 앱이 9.0→11.0으로 메이저 버전을 올리는 동안 릴리스 노트가 모두 "버그 수정"이라 현재 상태는 알 수 없다.

Composer는 이전 보고서의 해석을 보정해야 한다. 이전에는 만들기·검증·실행이 "하나의 전략 상세 화면 안에서 이어진다"고 썼다. 이번 검색에서 확인된 Composer 도움말은 iOS 앱을 **읽기 전용 베타 동반 앱**으로 설명한다. 탭은 **Portfolio와 Discover**뿐이고, 관심종목·시뮬레이션 성과·입출금·계좌 개설은 지원하지 않는다 ([Composer Help](https://help.composer.trade/article/115-ios-public-beta-information)). 다만 이 URL은 2026-10-18 직접 열람 시 404여서 내용은 검색 색인에 의존했다. 백테스트(기본 10만 달러, 수수료·슬리피지 반영)는 웹 기능이다 ([Composer Help](https://help.composer.trade/article/215-7-how-do-backtests-work-in-composer)). 즉 전략의 생애주기는 한 화면에 이어지되, **그 화면은 웹에 있고 모바일은 감시와 둘러보기만 한다**. Apple Stocks는 여전히 탭 없는 단일 화면(관심종목 + Business News)이다 ([App Store](https://apps.apple.com/us/app/stocks/id1069512882)). iOS 26/27에서 Stocks 앱 내비게이션이 바뀌었다는 근거는 찾지 못했다.

| 앱 | 확인된 탭/구조 | 등급 | 출처·날짜 |
|---|---|---|---|
| Yahoo Finance | Home · News · Search · Markets · Account | **확인**(공식, 날짜 없음; 2026년 현행 여부 미확정) | [Yahoo Help](https://help.yahoo.com/kb/SLN22069.html) |
| TradingView | Menu(안에 Notifications) | **확인**(App Store 2026 추정) | [App Store](https://apps.apple.com/us/app/tradingview-track-all-markets/id1205990992) |
| TradingView | Watchlist · Chart · Ideas · News · Profile | **미확인**(제3자 영상 2025-05) | [Optimus Futures](https://optimusfutures.com/blog/master-the-tradingview-mobile-dashboard-in-seconds-tradingview-mobiletrading-trading/) |
| Seeking Alpha | 좌상단 햄버거 사이드 메뉴 | **부분 확인**(공식, 낡았을 수 있음) | [SA Help](https://help.seekingalpha.com/iphone-app/where-can-i-find-my-investing-group-subscriptions-on-the-iphone-ipad-app) |
| Composer (iOS 베타) | Portfolio · Discover (읽기 전용) | **부분 확인**(공식이나 현재 404) | [Composer Help](https://help.composer.trade/article/115-ios-public-beta-information) |
| Apple Stocks | 탭 없음, 관심종목+뉴스 단일 화면 | **확인**(App Store 설명) | [App Store](https://apps.apple.com/us/app/stocks/id1069512882) |

## 국내 앱은 홈 상단 탭 개편과 "왜 움직였나" 경쟁에 집중한다

국내 앱 분석은 이전 보고서 내용을 유지한다. 핵심은 세 가지다. 첫째, 국내 대형 증권사 7곳(키움·미래에셋 MAPS·삼성·KB·NH·한투·신한)과 토스증권·카카오페이증권의 **하단 탭 전체 구성은 여전히 미확인**이다. 확인된 것은 진입 구조뿐이다. 토스증권은 토스 앱 하단 [증권]으로 들어가 오른쪽 위 돋보기로 검색하고, 종목 화면 아래 [구매하기]/[판매하기]로 주문한다 ([토스 FAQ](https://support.toss.im/faq/3317)). MTS 안에 '전체' 탭과 '발견'·'인사이트'가 있다 ([헤럴드경제 2026-03-24](https://www.heraldk.com/article/2026032416194438288)). 웹 최상위 메뉴는 홈 · 피드 · 주식 골라보기 · 내 계좌다 ([tossinvest.com](https://www.tossinvest.com/feed/following)). 네이버페이 앱 하단에는 2023년 12월부터 '증권' 탭이 있다 ([NAVER](https://www.navercorp.com/media/pressReleasesDetail?seq=2406)). 2024년 무렵 토스증권 하단 바가 "홈 · 관심 · 발견 · 피드 · 전체"였다는 기억은 출처가 없으므로 사실로 쓰지 않는다. 토스증권 '시장' 탭(2026-10-14)과 키움 "주식 · 커뮤니티 · 상품 · 혜택"(2026-09)이 하단 탭인지 홈 상단 탭인지도 미확인이다 ([글로벌이코노믹](https://www.g-enews.com/article/Securities/2026/10/202610141114582205923c501012_1); [아시아경제](https://core.asiae.co.kr/article/2026090808272426368)).

둘째, 2024~2026년 국내 개편은 하단 탭이 아니라 **홈 상단 탭**에서 일어났다. 신한 SOL증권 MY홈·주식 홈·연금/상품 홈·AI 홈(2025-09; [헤럴드경제](https://www.heraldk.com/article/2025083117014107623)), 미래에셋 MAPS의 '발견'·'내 투자'(2026-10; [토요경제](https://sateconomy.co.kr/news/view/1065573060591216)), iM증권 '내 투자 / 오늘의 시장 / 금융상품'(2026-10; [아시아경제](https://core.asiae.co.kr/article/2026101408590354852))이 그 예다. 키움은 "잔고를 홈에서 바로 보여 달라"는 요청이 가장 많아 홈에 보유 종목과 계좌별 손익을 넣었다 ([아시아경제](https://core.asiae.co.kr/article/2026090808272426368)). 이는 해외 기존 증권사의 계좌 우선 첫 화면과 같은 방향이다. 키움은 조건검색식 작성·성과 검증 같은 전문 기능을 전체메뉴 깊은 곳에 유지한다 ([App Store](https://apps.apple.com/kr/app/id1570370057)).

셋째, "왜 움직였나"는 국내에서도 표준이 됐다. 토스증권은 종목 단위 AI 시그널 ([헤럴드경제 2026-05-14](https://www.heraldk.com/article/2026051418383462717)), 이슈 단위 실시간 이슈(핵심 이슈 20개), 시장 단위 '시장' 탭(매시간 AI 시장 요약, 이슈 타임라인, 근거 기사 기반 AI 채팅)으로 세 층위를 갖췄다. 네이버페이 종목토론방은 2025년 7월부터 '삼성전자 소식' 같은 자동 계정이 주가 변동·공시·리서치를 토론 피드에 올린다 ([전자신문](https://www.etnews.com/20250704000182)). 국내 핀테크·대형 증권사의 모의투자 진입 경로는 이번에도 확인하지 못했다.

## 여섯 가지 축에서 본 교차 패턴

아래 두 표는 앱별로 여섯 축을 정리한 것이다. 칸마다 등급을 괄호로 붙였다(확=확인, 부=부분 확인, 미=미확인). 근거는 위 섹션의 출처와 같다.

**표 A. 첫 화면, 주문 진입, 검색 위치**

| 앱 | 첫 화면 유형 | 주문 진입 방식 | 검색 위치 |
|---|---|---|---|
| Robinhood | 보유 자산을 쌓은 홈(부) | 종목 화면 Trade 버튼, IRA는 계좌 선택 후 Trade(확) | Search 목적지가 발견 피드 겸용(부) |
| Public | 포트폴리오(확) | 미확인 | 미확인 |
| SoFi / Cash App | 슈퍼앱 홈 → Invest 탭 / Money 탭 타일(확) | SoFi Invest 홈 빠른 메뉴(부) / 미확인 | Cash App: Money → Stocks 안 검색(확) |
| Fidelity Standard | Home(사용자 편집 가능, 2025-07)(확) | Transact > Trade 또는 검색 결과에서 바로(확) | 심볼 검색에서 주문 티켓(확) |
| E*TRADE | Accounts(부) | Trade 탭(부) | 미확인 |
| thinkorswim | Overview(부) | Trade 탭(부) | 미확인 |
| IBKR GT | 미확인(Home 탭 없음) | Portfolio 안 Trade 상단 탭(확) | Explore(확) |
| Vanguard | Portfolio 추정(미) | Transact 탭(확) | Portfolio·Transact 탭 안 검색(확, 2026-04) |
| Webull | 미확인 | Markets·Watchlists·Feeds에서 우상단 돋보기 → 종목 → Trade(확) | 우상단 돋보기(확); 11.0은 Markets 하단 고정(부) |
| moomoo | Watchlist(확) | 종목 화면(미) | 미확인 |
| Trading 212 | Home(총액·위젯·관심종목, 2025-09)(부) | 미확인(중앙 아이콘 정체 미상) | 미확인 |
| Trade Republic | Wealth(포트폴리오)(확) | 종목 화면 오른쪽 아래 "Handeln" 버튼(부) | 하단 떠 있는 Search(부) |
| Scalable | 포트폴리오/브로커 홈(부) | 미확인 | "Finden" 탭(부) |
| eToro (새 앱) | 미확인 | "fewer taps to trade"(확), 위치 미상 | 미확인 |
| Wealthsimple | Home(계좌 고정 가능)(확) | 미확인 | 하단 돋보기 → Discover(확) |
| Yahoo Finance | Home(포트폴리오·관심종목)(확) | 해당 없음(정보 앱) | Search 탭(확) |
| TradingView | 미확인 | 차트 안 톱니 → Trading, 가격축 "+"(확) | 미확인 |
| 토스증권 | 계좌 잔고 우선 홈(부, 이전 보고서) | 종목 화면 [구매하기]/[판매하기](확) | 우상단 돋보기(확) |

**표 B. 모의투자, AI '왜 움직였나', 커뮤니티 위치**

| 앱 | 모의투자 위치 | AI '왜 움직였나' 위치 | 커뮤니티 위치 |
|---|---|---|---|
| Robinhood | 미확인 | 종목 상세 Cortex Digests + Investing 안 Portfolio Digests(Gold, IRA 제외)(확) | Robinhood Social 베타 종료(확), 위치 미상 |
| Public | 없음(부) | 차트 위 Key Moments, Alpha 요약(확) | 미확인 |
| Fidelity | 없음(부) | Markets 탭 Morning Market Briefing(확, 2026-08) | 해당 없음 |
| thinkorswim | paperMoney 모바일 포함(확), 경로 More 추정(미) | 미확인 | 해당 없음 |
| Power E*TRADE | 로그인 화면 Paper 탭 + 노란 배너(부) | 미확인 | 해당 없음 |
| IBKR GT | 미확인 | 2025-12 개편 AI 뉴스 요약(부) | 해당 없음 |
| Webull | Markets → paperTrade(확) | "News & Daily" AI 요약 버튼(부) | Feeds 탭(확) |
| moomoo | Discover → Papertrade 하위 탭(확) | 미확인 | Discover → Moo(확) |
| Trading 212 | More → Switch to practice(부) | AI 챗봇(2026-04, 진입점 미상)(부) | More → Learn → 포럼(부) |
| eToro | 웹: 사용자 이름 아래 Real/Virtual(확), 모바일 미확인 | 화면 위 Tori 아이콘, 먼저 알림(확/부) | 미확인(새 앱) |
| Tiger | 데모 계좌 제공(확), 위치 미상 | TigerAI 가격 변동 분석(부) | Trade Feed·Leaderboard(부) |
| 富途牛牛 | 미확인 | 資訊·社區 안 AI 진입점(부) | 牛牛圈(부) |
| Stocktwits | 해당 없음 | 트렌딩 목록 항목별 "Why It's Trending" AI 한 줄(부) | Home·Trending 스트림(부) |
| TradingView | 차트 Trading Panel → Paper Trading 브로커(확) | 해당 없음(News Flow 필터 스트림)(확) | Ideas(미) |
| Composer | 해당 없음 | 해당 없음 | 해당 없음(Discover는 전략 둘러보기)(부) |
| 토스증권 | 미확인 | 종목 AI 시그널 · 실시간 이슈 · '시장' 탭(확, 탭 위치 미상) | 계좌 기반 커뮤니티, 웹 '피드'(확) |
| 네이버페이 증권 | 미확인 | 종목토론방 자동 소식 계정(확) | 종목토론방(확) |

두 표에서 패턴 네 가지가 읽힌다. 첫 화면은 계좌·포트폴리오(Fidelity, E*TRADE, Trade Republic, Wealthsimple, 토스, 키움)와 관심종목(moomoo, Yahoo, Stocktwits)으로 나뉜다. **이벤트 피드를 첫 화면으로 둔 브로커는 확인된 사례가 없다.** 주문은 탭인 곳(E*TRADE, thinkorswim, Vanguard Transact)이 모두 오래된 적극 투자자형 구조이고, 2025~2026년에 개편한 앱(IBKR GT, Trade Republic, Webull, TradingView, 토스)은 **종목·차트 화면의 행동**으로 둔다. 모의투자는 확인된 일곱 곳 모두 하단 탭이 아니다. 가장 가까운 moomoo도 Discover의 하위 탭이다. AI 설명은 확인된 사례 전부가 **종목 상세·포트폴리오·목록 항목·떠 있는 아이콘**에 붙고, 독립 AI 탭은 없다.

## 이전 보고서 대비 바뀐 것

**정정된 내용.** IBKR GT의 Home 탭은 "자료 충돌"에서 "**사라짐**"으로 정리했다. 2025 릴리스 노트가 Forecasts·Watchlist·Portfolio·Explore만 하단 탭으로 밝히고, Home 목록은 IMPACT 앱과 같은 낡은 강의 문구다. Webull 모의투자는 "Open Account 탭 안(2024 싱가포르), 미국판 미확인"에서 공식 도움말 기준 **Markets → paperTrade**로 고쳤다. Webull Account가 하단 중앙이라는 점도 공식 확인했다. moomoo는 "고정 6탭"에서 "**6탭 문서와 고정 5탭+임시 영역 Dock 문서가 충돌**"로 고쳤다. Trading 212 철회는 "약 10일 만에 되돌리겠다고 밝혔다"를 2025-12-24 공지, 2026-01-12 v8.14.1 배포 시작, **부분 철회**(Y축·필터 미복구)로 정밀화했다. 그 개편이 탭 바가 아니라 Portfolio 화면을 바꾼 것이라는 점도 덧붙였다. Composer는 "만들기·검증·실행이 하나의 전략 상세 화면"에서 "**제작·백테스트는 웹, iOS는 Portfolio·Discover 읽기 전용 베타**"로 보정했다. Apple `.prominent`는 "WWDC26에서 추가(2차 출처)"에서 "**`TabRole.prominent`는 Apple 공식 문서에 존재하나 iOS 버전 미표기, HIG(2026-06-08 갱신)에는 언급 없음**"으로 바꿨다. HIG의 "5개 이하"는 사용자화 가능한 탭의 기본값에 대한 권고이고 일반적인 상한이 아니라는 점도 분명히 했다.

**새로 확인된 내용.** Robinhood의 Investing·Crypto·Account 탭 이름(공식), Predict 탭 발표(2026-09-29, 언론 경유), 옵션은 Investing 안, IRA는 계좌 선택. SoFi 하단 Invest(공식), Cash App 좌하단 Money와 Stocks 타일(공식), Public 좌상단 ≡ 메뉴(공식)와 Markets 탭(제3자). Fidelity Standard 5탭과 Trader+ 모드의 탭 이름 매핑, Power E*TRADE Menu 탭(App Store 2025-08-13), Vanguard 탭 이름과 Research→Explore 개명(2026-05). Trading 212 Home·Portfolio·Card·More와 More 안의 Switch to practice, Revolut Invest 탭, Freetrade Activity 탭(2025-07-11), Scalable "Finden"·Profile, Trade Republic Wealth/Cash. eToro 새 앱 배포 일정(2026-10-04 이전, v700.x 10-06). Wealthsimple 스와이프 구조(2025-08-18), Tiger·Stake·富途牛牛 부분 정보, SBI証券 Plus 허브 앱. Yahoo Finance 5탭(공식), TradingView Menu→Notifications, Seeking Alpha 사이드 메뉴. Apple 쪽에서는 `tabBarMinimizeBehavior`(iOS 26.0), `tabViewBottomAccessory(isEnabled:)`(iOS 26.1+), WWDC25 세션 323의 검색 역할과 하단 액세서리, HIG 변경 이력(2025-07-28, 2025-12-16, 2026-06-08)을 1차 출처로 확인했다.

**여전히 미확인인 내용.** 아래 앱은 하단 탭 전체(또는 순서)를 출처로 확인하지 못했다. Robinhood·Public·SoFi·Cash App의 순서, Schwab Mobile 전체, Fidelity Trader+ 나머지 탭, IBKR Mobile 2025~2026 기본 탭, eToro 새 앱 탭 바와 모바일 Real/Virtual 위치, Revolut 메인 앱 나머지 탭, Bitpanda 전체, Trading 212 중앙 아이콘, Stocktwits·TradingView 순서, 일본 증권사 전부(SBI·楽天·マネックス·PayPay·eスマート), Tiger·Stake 전체, 국내 대형 증권사 7곳과 토스·카카오페이·네이버페이 증권 내부 탭. Robinhood Predict 탭의 전체 배포 여부, iOS 27에서 검색 탭이 인라인으로 돌아가는지, `.prominent` 탭의 시각적 동작도 미확인이다. 경쟁 앱 가운데 Fidelity를 빼면 Liquid Glass 도입 공식 발표도 없다.

## Apple 탭 바 규칙은 1차 출처와 루머가 뚜렷이 갈린다

1차 출처로 확인된 것부터 정리한다. HIG는 "탭 바는 끝쪽(trailing)에 전용 검색 탭을 둘 수 있다"고 쓴다. 공간이 부족해 생기는 More 탭은 "콘텐츠에 닿고 알아차리기 어렵게 만든다"고 경고한다. 사용자가 탭을 고를 수 있다면 "기본 목록은 5개 이하"를 권한다. 아이패드에서는 탭 바를 사이드바로 바꾸는 `sidebarAdaptable`을 복잡한 앱의 선택지로 제시한다 ([HIG Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars), 2026-06-08 갱신). 하단 액세서리에 대해 HIG는 Music의 MiniPlayer를 예로 든다. 스크롤하면 탭 바를 최소화하고 액세서리를 탭 바와 한 줄로 합칠 수 있으며, 사용자가 탭을 누르거나 맨 위로 스크롤하면 원래대로 돌아온다. WWDC25 세션 323은 검색 역할 탭을 고르면 "검색 필드가 탭 바 자리를 차지한다"고 설명하고, `tabBarMinimizeBehavior(.onScrollDown)`와 `tabViewBottomAccessory`, 배치 환경값 `tabViewBottomAccessoryPlacement`(`.inline`일 때 압축 레이아웃)를 소개한다 ([WWDC25 #323](https://developer.apple.com/videos/play/wwdc2025/323/)). `tabBarMinimizeBehavior`는 iOS 26.0부터다 ([SwiftUI 문서](https://developer.apple.com/documentation/swiftui/view/tabbarminimizebehavior(_:))). `tabViewBottomAccessory(isEnabled:content:)`는 **iOS 26.1부터**이고, 문서 예제는 `isEnabled: hasStatusUpdate`로 **상태 업데이트가 있을 때만** 액세서리를 띄운다 ([SwiftUI 문서](https://developer.apple.com/documentation/swiftui/view/tabviewbottomaccessory(isenabled:content:))). `TabRole`에는 `search`와 `prominent` 두 속성이 있지만, `prominent`가 어느 iOS 버전부터인지는 페이지에 나오지 않는다 ([TabRole](https://developer.apple.com/documentation/swiftui/tabrole)).

2차 출처나 루머로만 뒷받침되는 것도 있다. `.prominent`가 "검색처럼 끝쪽에 떨어지는 탭"이라는 설명은 개발자 블로그에서 나왔다 ([Swift with Majid 2026-06-08](https://swiftwithmajid.com/2026/06/08/what-is-new-in-swiftui-after-wwdc26/)). UIKit `prominentTabIdentifier`도 마찬가지다 ([Swiftjectivec](https://swiftjectivec.com/iOS-27-Notable-UIKit-Additions/)). iOS 26.4 베타 1(2026-02-16 보도)에서 App Store 검색 탭이 다시 하단 바 안으로 들어갔다는 관찰이 있다 ([Manton Reece](https://www.manton.org/2026/02/16/tomac-blogging-about-a-change.html)). iOS 27에서 여러 시스템 앱의 검색 탭이 다른 탭과 나란히 놓인다는 이야기는 Bloomberg 유출 보도다 ([9to5Mac](https://9to5mac.com/?p=1052059)). Xcode 27 베타에서 `.prominent` 검색 탭이 시스템 앱처럼 변형되지 않는다는 개발자 포럼 보고도 있다 ([Apple Developer Forums](https://developer.apple.com/forums/thread/834952)). 그래서 실무 지침은 이전 보고서와 같다. 검색은 직접 그린 버튼이 아니라 `Tab(role: .search)`로 선언해 위치를 시스템에 맡긴다. `.prominent`는 HIG가 다룰 때까지 설계의 전제로 삼지 않는다. 표준 `TabView`를 써야 액세서리·최소화·Liquid Glass를 시스템에서 받는다. 경쟁 핀테크 앱 상당수가 커스텀 탭 바라서 Liquid Glass 근거가 없다는 점은 monticker가 표준 컴포넌트로 빠르게 차별화할 여지이기도 하다.

## monticker 4탭안은 유지하고, F안은 조건부 상태 바로 좁힌다

**4탭안(피드·관심종목·내 계좌·검색)은 근거가 더 단단해졌다.** 확인된 해외 앱의 정보 구조를 탭 이름 수준으로 묶으면 네 축으로 모인다. 내 자산(Accounts/Portfolio/Investing/Wealth), 관심종목(Watchlist/Watch), 발견·시장(Explore/Discover/Markets/Finden), 프로필·더보기(Menu/More/Me/Profile)다. monticker는 이 가운데 "발견·시장"을 "피드"와 "검색"으로 나누고, 프로필·더보기를 탭에서 뺐다. 검색 탭은 Yahoo Finance의 Search 탭, Scalable의 Finden, Wealthsimple의 돋보기→Discover와 같은 자리이고, iOS 26의 검색 역할과 그대로 맞물린다. 프로필·설정은 Trade Republic(좌상단 아바타), Public(좌상단 ≡), Robinhood(Account 안 Menu)처럼 내 계좌 탭 안이나 상단 아바타로 넣으면 된다. 그러면 HIG가 경고하는 More 탭을 피할 수 있다. 주문을 탭으로 두지 않는 점은 2025~2026년 개편 앱들(IBKR GT, Trade Republic, Webull, TradingView, 토스)과 같다.

**첫 탭을 피드로 하는 것은 여전히 차별화이자 위험이다.** 확인된 브로커 가운데 피드가 첫 화면인 곳은 없다. Trading 212의 사례가 보여 주듯, 투자 앱 사용자는 보유 종목과 차트 축처럼 익숙한 밀도를 빼앗기면 이탈하겠다고 반발하고, 회사는 수주 안에 되돌렸다. Trading 212 커뮤니티에서는 "애초에 누가 Home으로 들어가느냐, 우리 앱은 파이와 투자로 열린다"는 반응도 나왔다 ([T212 커뮤니티](https://community.trading212.com/t/your-home-screen-just-got-a-new-look/87886)). 반대로 Robinhood Portfolio Digests(Investing 안에 포트폴리오 단위 상승·하락 요인), eToro Tori의 선제 알림, Stocktwits 목록 항목별 AI 한 줄은 "내 종목에 무슨 일이 있었나"가 첫 화면 가까이로 올라오는 흐름을 보여 준다. 따라서 피드 탭 맨 위에는 일반 뉴스가 아니라 **보유·관심 종목에 묶인 이벤트 카드**와 작은 자산 요약이 와야 한다. 각 카드에는 근거 출처를 보여 줘야 한다. Google Finance Deep Search가 인용과 연구 계획을 노출하는 방식은 monticker의 컴플라이언스 입장에도 맞는다 ([Google 블로그 2025-11-06](https://blog.google/products/search/new-google-finance-ai-deep-search/)). 사용자 인터뷰(ADR-105)에서 "앱을 열면 피드와 계좌 중 무엇을 기대하는가"를 묻는 것은 여전히 가장 싼 검증이다.

**F안(하단 액세서리 계좌 바)은 무엇을 담느냐에 따라 평가가 갈린다.** Apple 1차 출처는 액세서리를 MiniPlayer처럼 "탭을 넘나들며 계속 이어지는 상태"를 위한 자리로 그리고, `isEnabled:` 예제는 "상태 업데이트가 있을 때만" 띄우는 쓰임을 보여 준다. 금융 앱 선례도 같은 방향이다. Power E*TRADE는 모의 모드에서 **노란 배너**를 계속 띄우고 ([Brokerage-Review 2026-09-19](https://www.brokerage-review.com/online-trading/virtual/etrade-paper-trading.aspx)), Trading 212는 practice 전환을 More에 두며, eToro는 Real/Virtual을 계정 메뉴에 둔다. 모의투자를 계좌 모드로 다루는 업계 관행과, 실전/모의 혼동을 막는 상시 표시를 결합하면 액세서리가 가장 자연스러운 자리다. 반면 **잔고 총액을 늘 보여 주는 바**로 쓰면 근거가 약해진다. 내 계좌 탭과 정보가 겹치고, Trading 212가 2026년에 "Privacy Mode"를 내놓을 만큼 잔고 노출을 꺼리는 사용자가 있으며 ([T212 What's new](https://community.trading212.com/c/whats-new/13)), 긴 이벤트 피드를 읽을 때 화면 높이를 계속 차지한다. 권고안은 다음과 같다. 액세서리에는 현재 계좌 모드(모의/실전), 실행 중인 전략 수, 미체결 주문 같은 **진행 중인 상태**만 담는다. 금액은 가릴 수 있게 한다. 표시할 것이 없을 때는 `isEnabled:`로 숨긴다(iOS 26.1+, 26.0은 기본 변형으로 대체). 액세서리를 누르면 내 계좌 탭이나 시트로 간다. 실거래 매수/매도 버튼은 넣지 않는다. HIG가 탭 바를 행동이 아닌 이동용으로 규정하고, 실주문 오조작 위험과 monticker의 검증 우선 원칙(validation-hardening-plan)을 고려하면 주문은 종목 화면의 명시적 흐름에 두는 편이 맞다. 피드 탭에는 `tabBarMinimizeBehavior(.onScrollDown)`를 켜서, 스크롤하면 액세서리가 탭 바와 한 줄로 접히게 한다.

**퀀트랩과 전략 마켓은 이번 근거로 2차 표면 판단이 더 굳어졌다.** Composer는 iOS 앱에서 제작과 백테스트를 빼고 Portfolio(실행 중 전략 감시)와 Discover(전략 둘러보기)만 남겼다. Robinhood의 전략 도구도 데스크톱 Legend에 있다. moomoo와 富途牛牛의 커뮤니티·학습·모의투자도 Discover 같은 발견 탭 안의 하위 항목이다. 그러니 monticker 모바일에서는 "내 전략 실행 현황"을 내 계좌 탭 안에, "전략 마켓 둘러보기"를 검색·피드의 발견 영역에 두고, 랩 제작은 웹에 남기는 구성이 업계 선례와 맞다. 아이패드에서는 `sidebarAdaptable`로 사이드바 항목을 늘려 랩을 노출할 수 있다. Fidelity Standard/Trader+처럼 간편/전문 모드에서 같은 콘텐츠를 다른 탭 이름으로 보여 주는 방식, moomoo Dock처럼 Discover만 고정하고 나머지를 사용자가 고르게 하는 방식은 사용량이 쌓인 뒤 다섯 번째 탭을 검토할 때 참고할 두 가지 선례다.

## Conclusion

이번 재검증으로 바뀐 것은 "업계 표준 탭"에 대한 기대치다. 공식 출처로 전체 순서까지 확인되는 앱이 두 곳뿐이라는 사실 자체가 시사점이다. 해외 앱들도 탭 이름보다 **모드(간편/전문, 실전/모의), 덧씌우는 AI, 화면 안의 밀도 선택(eToro advanced mode, Fidelity immersive view)**에서 실험하고 있다. 탭 바는 4~6칸의 안정된 그릇이고, 2025~2026년의 변화는 그 위에 얹는 층에서 일어난다. 그러니 monticker의 차별화 지점은 탭 구성이 아니라 피드의 이벤트 카드 품질, 그리고 모의/실전 상태를 앱 어디서나 분명히 보여 주는 장치다. 후자는 iOS 26 하단 액세서리라는 시스템 컴포넌트와 정확히 맞물리고, 경쟁 브로커 중 이를 공식적으로 쓰는 곳은 확인되지 않았다.

다음 단계는 두 가지다. 첫째, 미확인 칸을 실제 기기 캡처로 메운다. 우선순위는 국내 직접 경쟁자(토스증권, 키움, 미래에셋 MAPS)와 이벤트 설명 경쟁자(Robinhood, eToro 새 앱)다. 둘째, F안을 "잔고 바"가 아닌 "상태 바"로 프로토타입해서 모의 모드 인지율과 피드 스크롤 체감을 측정한다. 이 보고서의 표는 그 결과로 갱신되기 전까지 "확인"으로 표시된 칸만 설계 근거로 쓴다.
