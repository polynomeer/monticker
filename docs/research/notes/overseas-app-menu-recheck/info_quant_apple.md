# Information, Charting, and Quant Apps: Mobile Navigation Re-verification, plus Apple Stocks and the iOS 26/27 Tab Bar

Research date: 2026-10-18. About 30 search/fetch calls. This builds on `research_notes/주식거래 앱 메뉴 구성 비교/global_social_quant_and_ios_hig.md`.

**Confidence labels**
- **Verified:** the vendor's own documentation names the tab labels, or Apple developer docs confirm the API.
- **Partially verified:** some labels or placements come from the vendor, but the full set or order does not.
- **Unverified:** only third-party or old sources were found.

App Store screenshots could not be read with the tools (images come back as placeholders). Nothing here replaces checking on a device.

## Q1. Each app's bottom tabs, home screen, and where features live

### Takeaway
Yahoo Finance is the only app in this set whose official help page documents a five-tab bar: **Home, News, Search, Markets, Account**. That page is undated (Verified for labels, but undated). TradingView has a confirmed "Menu" that now holds a Notifications screen (App Store release notes, 2026). Its other tabs come only from a May 2025 third-party video: Watchlist, Chart, Ideas, News, Profile/Menu (Partially verified). Paper trading is not a tab in TradingView. It is a broker connection that you open from the chart.

Seeking Alpha's help center still describes a top-left **hamburger side menu** and a "left nav bar," not a bottom tab bar (Partially verified, possibly stale). Composer's iOS app is a read-only beta companion with only **Portfolio** and **Discover**. Strategy creation and backtesting stay on the web. Apple Stocks remains a single-screen watchlist plus Business News app. No iOS 26/27 Stocks-specific navigation change was found.

### Cited Findings

**Yahoo Finance (iOS)**
- **[Verified labels, undated]** The official help page lists five tabs:
  - "Home": "View your portfolios, watchlists and holdings on the Home tab."
  - "News": "top financial news headlines, videos and articles."
  - "Search": "Follow curated watchlists, look up quotes and see earnings reports."
  - "Markets": "track performance of global indices and their significant changes."
  - "Account": "manage account settings, check notifications and set custom alerts."

  The page has no visible publish or update date, and the order above is the order the page lists them. — [Yahoo Help SLN22069: Getting started with the Yahoo Finance mobile app for iOS](https://help.yahoo.com/kb/SLN22069.html)
- Where things live: portfolios and watchlists are on Home. **Alerts and notifications are under Account**, not a separate tab. Screeners and curated lists are under Search. — [Yahoo Help SLN22069](https://help.yahoo.com/kb/SLN22069.html)
- A third-party blog lists the tabs as "Home, My Portfolio, Markets, News, Search." This conflicts with Yahoo's own help, so treat it as unreliable. — [sophiecress.com](https://sophiecress.com/blog/ios-yahoo-finance-app-a) vs [Yahoo Help](https://help.yahoo.com/kb/SLN22069.html)
- 2026 App Store release notes: versions 26.6.0–26.7.2 (Mar 23 – Apr 15, year inferred from the version numbering) added **live earnings call listening**, with an audio player under the chart on the Quote page. Versions 26.8–26.20 (through Oct 11) mention only bug fixes. The listing describes brokerage sync ("track your entire net worth"), custom price triggers, news alerts, and a personalized news feed. — [App Store: Yahoo Finance](https://apps.apple.com/us/app/yahoo-finance-stock-market/id328412701)
- Nov 2023 redesign: a portfolio overhaul and a news hub, with display ads cut by 40%. — [BusinessWire 2023-11-07](https://www.businesswire.com/news/home/20231107696257/en/Yahoo-Finance-Debuts-New-Design-and-Features-to-Empower-Everyday-Investors)
- Jun 1, 2026: Yahoo Finance launched a dedicated **AI news hub** with Axios, Fortune, TechCrunch, Semafor and Fast Company. This is content aggregation about AI, not an AI summarizer. — [Yahoo Inc. press, 2026-06-01](https://unified.www.yahooinc.com/press/yahoo-finance-launches-dedicated-hub-for-ai-news-and-analysis)
- Jun 2026: Yahoo **Taiwan's** Finance app added a VIP-only "AI Stock Pick Summary." This is a single secondary source. — [kocpc.com.tw](https://en.kocpc.com.tw/?p=3097)

**TradingView (iOS/Android)**
- **[Verified: "Menu" exists]** App Store release notes 2.140–2.142 (Sep 15 – about Oct 13; year inferred as 2026 because 2.127 in March required iOS 17) add a **Notifications screen reachable from the Menu**. Versions 2.138–2.139 (Aug) moved chart settings into the "Analysis hub." Version 2.124 (Feb) added recommended Ideas and Minds authors. — [App Store: TradingView](https://apps.apple.com/us/app/tradingview-track-all-markets/id1205990992)
- **[Unverified]** A broker walkthrough video from May 2025 lists a Chart icon plus Watchlist, Ideas, News and Profile tabs, a gear for settings, and "+" to add symbols. — [Optimus Futures](https://optimusfutures.com/blog/master-the-tradingview-mobile-dashboard-in-seconds-tradingview-mobiletrading-trading/)
- The 2021 TradingView blog introduced "a menu tab for a quick access to your profile and all app settings." — [TradingView blog](https://www.tradingview.com/blog/en/you-ve-improved-our-mobile-apps-25189/)
- **Paper trading placement:** official help says to open Supercharts, then the **Trading Panel** at the bottom, then select "Paper Trading" from the broker list. It is a broker connection, not a tab. — [TradingView: connect/disconnect Paper Trading](https://www.tradingview.com/support/solutions/43000479975-how-to-connect-or-disconnect-a-paper-trading-account/); [Paper trading main functionality](https://www.tradingview.com/support/solutions/43000516466-paper-trading-main-functionality/)
- **Mobile order placement (official):** tap the gear in the lower-right of the chart, open Chart Settings, then the "Trading" tab, and toggle "Instant orders placement." Pending orders use the "+" button on the price scale. All of this lives inside the chart, not in a tab. The page shows no date (©2026; image timestamps suggest about Nov 2025). — [TradingView: How to place orders in the mobile app](https://www.tradingview.com/support/solutions/43000707412-how-to-place-orders-in-the-mobile-app/)
- The App Store description lists cloud alerts (email, app, desktop, webhook), Pine Script, Bar Replay, paper trading, integrated brokers, an economic and earnings calendar, and community Ideas. — [App Store: TradingView](https://apps.apple.com/us/app/tradingview-track-all-markets/id1205990992)

**Seeking Alpha**
- **[Partially verified, possibly stale]** iPhone help: "Tap the 'Hamburger' icon on the top-left to reveal the side menu." Investing Group subscriptions are found there. — [SA Help: Investing Group subscriptions on iPhone](https://help.seekingalpha.com/iphone-app/where-can-i-find-my-investing-group-subscriptions-on-the-iphone-ipad-app)
- Portfolio value: tap "All Portfolios" from the **left nav bar**. Landscape portfolio views include After Hours, Holdings, Earnings, Dividends, Value, Growth, Performance, Momentum, Profitability, Ownership and Debt. — [SA Help: check portfolio value on mobile](https://help.seekingalpha.com/how-to-check-portfolio-value-on-mobile); [SA Help: view full portfolio on iPhone](https://help.seekingalpha.com/migration/iphone-app/how-to-view-full-portfolio-on-iphone)
- Alerts: a bell icon labeled "Manage Alerts" at the top of a portfolio page. — [SA Help: real-time alerts](https://help.seekingalpha.com/how-do-i-set-up-real-time-alerts-for-my-seeking-alpha-portfolio)
- The App Store version history runs 8.5.2 (2025-06-23) to 11.0.0 (Aug 31, presumably 2026). Every note says "bug fixes and performance improvements," so the major version jumps (9.0.0 on 2025-08-20, 10.0.0 on Jan 19, 11.0.0 on Aug 31) are undocumented. The description lists Quant Ratings, stock/ETF screeners and rankings (Premium), plus real-time news and alerts (free). — [App Store: Seeking Alpha](https://apps.apple.com/us/app/seeking-alpha-stock-market/id552799694)

**Composer**
- **[Verified scope, undated, possibly stale]** Composer's help says the iOS app is a beta companion that "may not work as intended." It shows only **Portfolio and Discover**. Watchlist is not available, and it shows live but not simulated performance. Deposits, withdrawals, Trading Pass management and new accounts are not available. It is "read-only," a tool to monitor, not manage. — [Composer Help: iOS Public Beta Information](https://help.composer.trade/article/115-ios-public-beta-information) (this URL returned 404 on direct fetch on 2026-10-18; the content comes from the search index, so the article may have been retired or replaced)
- A backtest is "the basic way for any user to evaluate the logic and performance of any Composer symphony." It defaults to $100K and includes fees and slippage. This is a web feature. — [Composer Help: How do backtests work](https://help.composer.trade/article/215-7-how-do-backtests-work-in-composer)

**Simply Wall St**
- **[Partially verified, web]** The Watchlist is reached through a "Watchlist" tab in the platform header. There you can set your own fair value and write a Narrative. The help center sections are "Watchlist and Portfolios" and Portfolio Management (broker linking, manual portfolios, dividends). — [Simply Wall St Support: Getting Started With Watchlist](https://support.simplywall.st/hc/en-us/articles/8273417841935-Getting-Started-With-Watchlist) (403 on direct fetch; content from the search snippet); [Watchlist and Portfolios section](https://support.simplywall.st/hc/en-us/sections/4408533524623-Watchlist-and-Portfolios)

**Koyfin**
- A mobile app shipped in v3.20. Help lists watchlists, graphs, market movers, analyst estimates, price targets, market dashboards, company snapshots, news and transcripts. No tab names are given (published 2023-02-17, updated 2024-08-02). — [Koyfin Help: Mobile application](https://www.koyfin.com/help/mobile-application/); [Koyfin release notes v3.20](https://www.koyfin.com/help/release-notes/koyfin-mobile/amp/)

**Stocktwits (brief)**
- "Why It's Trending" is an **AI-powered** feature, according to a Stocktwits product-highlight podcast. A third-party scraper's schema shows an AI "why trending" summary on each trending symbol. — [Howard Lindzon / Trends with Friends](https://www.howardlindzon.com/p/new-trends-with-friends-episode-from-copilots-to-strip-mining-talent-from-start-ups-inside-ai-s-new); [Apify Stocktwits scraper](https://apify.com/automation-lab/stocktwits-scraper?fpr=data)
- 2025: a new sign-up flow builds a watchlist immediately and highlights trending insights and a Stocktwits MCP. — [Traders Union](https://tradersunion.com/news/market-voices/show/2872308-stocktwits-trending-watchlist/)
- **[Unverified]** The only tab list found is old: Trending, Watch List, Streams, Settings. — [id.nl](https://id.nl/huis-en-entertainment/computer-en-gaming/software/consumeren-en-delen-van-beursfeiten-en-meningen-55991)

**Apple Stocks**
- The App Store description covers one or more watchlists (stocks, indexes, funds, ETFs, currencies) with sparklines, sorting (manual, name, price change, % change, market cap) and Business News powered by Apple News. "Top Stories and Apple News stories are available in the US, UK, Canada, and Australia," and other regions get Yahoo news. No tab bar is described. — [App Store: Stocks](https://apps.apple.com/us/app/stocks/id1069512882)
- iOS 18.2 (Dec 2024) added pre-market quotes. 9to5Mac notes the app has been largely untouched since iOS 13. — [9to5Mac 2024-12-10](https://9to5mac.com/2024/12/10/apples-stocks-app-gets-a-rare-update-in-ios-182/)
- Multiple watchlists and sorting arrived in iOS 16.2. — [iDownloadBlog 2022-11-14](https://www.idownloadblog.com/2022/11/14/new-in-ios-16-2-apples-stocks-app-gets-watchlist-sorting-and-display-options/)

**Google Finance (brief)**
- The redesign was tested from Aug 2025. Deep Search launched Nov 6, 2025: Gemini runs "up to hundreds of simultaneous searches" and returns a cited answer with a visible research plan. Prediction-market data (Kalshi, Polymarket) and live earnings tracking were added. Deep Search was reported globally available after a May 11 announcement (year inferred as 2026). — [Google blog](https://blog.google/products/search/new-google-finance-ai-deep-search/); [ppc.land](https://ppc.land/google-finance-ai-lands-in-europe-with-deep-search-now-global/)

### Inferences
- In information apps, **alerts and notifications sit under Account/Menu** (Yahoo Account, TradingView Menu → Notifications), not in their own tab. **Search gets its own tab** in Yahoo, which matches the iOS 26 `Tab(role: .search)` pattern.
- **Trading and paper trading in a charting app live inside the chart** (TradingView Trading Panel, gear → Trading). That fits the HIG rule that tabs are for navigation, not actions.
- Quant apps (Composer) keep **create/backtest on the web**. Mobile is for monitoring live strategies (Portfolio) and browsing strategies (Discover). This supports a monticker mobile scope of "monitor running strategies + browse market" while the Lab authoring stays on the web.

### Gaps
- **TradingView:** the current tab labels and order were not confirmed by any official 2025–2026 source. Whether a separate "Explore/Markets" tab exists is unknown.
- **Yahoo Finance:** the help page is undated. Whether the 2026 app still uses these exact five tabs is not confirmed.
- **Seeking Alpha:** no source confirms whether the 9.x/10.x/11.x apps replaced the hamburger menu with a bottom tab bar.
- **Composer:** it is unknown whether the iOS beta article is still current (it now 404s) or whether a newer full app exists. No Composer Android info was found.
- **Simply Wall St, Koyfin, Stocktwits, Google Finance:** no source gives mobile bottom-tab labels.
- **Apple Stocks on iOS 26 and iOS 27:** no source (Apple Newsroom, iPhone User Guide, 9to5Mac, MacRumors) described Stocks-specific navigation changes. The iPhone User Guide article body did not load. The iOS 27 Newsroom URL I guessed returned 404.

## Q2. Apple HIG and WWDC: primary-source re-verification

### Takeaway
Apple primary sources confirm: the trailing search tab (HIG); `Tab(role: .search)` with `.searchable` replacing the tab bar with a search field (WWDC25 session 323); `tabBarMinimizeBehavior(.onScrollDown)` (iOS 26.0); the bottom accessory (`tabViewBottomAccessory`, plus a dynamic `isEnabled:` variant in iOS 26.1); `sidebarAdaptable`; and the guidance on tab counts. `TabRole.prominent` exists in Apple's SwiftUI documentation, but its iOS version is not stated on the page. The claim that iOS 27 system apps move search back inline comes only from secondary sources and beta reports. The HIG page (updated Jun 8 2026) still says "dedicated search tab at the trailing end" and does not mention `.prominent`.

### Cited Findings

**Primary source confirmed (developer.apple.com)**
- HIG: "A tab bar can include a dedicated search tab at the trailing end." — [HIG Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- HIG: "For tab bars with an attached accessory, like the MiniPlayer in Music, you can choose to minimize the tab bar and move the accessory inline with it when a person scrolls down. A person can exit the minimized state by tapping a tab or scrolling to the top of the view." — [HIG Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- HIG on iPadOS: the tab bar sits near the top and is "a fixed element, or with a button that converts it to a sidebar" (`tabBarOnly`, `sidebarAdaptable`). "Prefer a tab bar for navigation… If your app is more complex, you can provide the option to convert the tab bar to a sidebar." — [HIG Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- HIG on tab counts: "Use the appropriate number of tabs required… it's generally easier to navigate among fewer tabs." The trailing tab becomes "More" when space runs out ("makes it harder for people to reach and notice content"). "If you let people select their own tabs, aim for a default list of five or fewer." **There is no hard universal maximum; the "five" applies to customizable defaults.** — [HIG Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- HIG change log: Jun 8 2026 "Updated terminology and art"; Dec 16 2025 "Updated guidance for Liquid Glass"; Jul 28 2025 "Added guidance for Liquid Glass". The page contains **no mention of "prominent."** — [HIG Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- WWDC25 session 323, "Build a SwiftUI app with the new design":
  - "set a search role on one of your tabs and place a searchable modifier on your TabView… When someone selects this tab, a search field takes the place of the tab bar."
  - "the tab bar on iPhone floats above the content, and can be configured to minimize on scroll… use the tabBarMinimizeBehavior modifier… onScrollDown… re-expands when scrolling in the opposite direction."
  - "Place a view above the bar with the tabViewBottomAccessory modifier… read the tabViewBottomAccessoryPlacement from the environment" (`.inline` gives the compact layout).

  — [WWDC25 #323](https://developer.apple.com/videos/play/wwdc2025/323/)
- `tabBarMinimizeBehavior(_:)` is available from 26.0 on iOS, iPadOS, Mac Catalyst, macOS, tvOS, visionOS and watchOS. The doc example uses `.onScrollDown`. — [SwiftUI docs: tabBarMinimizeBehavior](https://developer.apple.com/documentation/swiftui/view/tabbarminimizebehavior(_:))
- `tabViewBottomAccessory(isEnabled:content:)` is iOS/iPadOS/Mac Catalyst **26.1+**. "On iPhone… when the tab bar is normal size, the accessory appears above it; when the tab bar is collapsed, the accessory displays inline." The doc example uses `isEnabled: hasStatusUpdate` to show a status view only when relevant. The base `tabViewBottomAccessory(content:)` came first (iOS 26.0, per WWDC25). — [SwiftUI docs: tabViewBottomAccessory(isEnabled:content:)](https://developer.apple.com/documentation/swiftui/view/tabviewbottomaccessory(isenabled:content:))
- `TabRole` lists two type properties: `search` ("The search role.") and **`prominent` ("The prominent role.")**. `TabRole` itself is iOS 18.0+. The page does not give per-property availability, so the iOS version for `.prominent` is not confirmed from the primary source. — [SwiftUI docs: TabRole](https://developer.apple.com/documentation/swiftui/tabrole)

**Secondary or beta-report only**
- `.prominent` is "for trailing-separated tabs, similar to search," e.g. `Tab("create", systemImage: "pencil", role: .prominent)`. — [Swift with Majid, 2026-06-08](https://swiftwithmajid.com/2026/06/08/what-is-new-in-swiftui-after-wwdc26/)
- UIKit `prominentTabIdentifier` / `setProminentTabIdentifier` marks one tab as visually prominent (search is given as an example). — [Swiftjectivec: iOS 27 UIKit additions](https://swiftjectivec.com/iOS-27-Notable-UIKit-Additions/)
- An Xcode 27 beta developer reports that the iOS 27 search tab gets the .prominent treatment (detached trailing). Activating it does not morph: the field goes to the top and the tabs don't collapse. This is unconfirmed. — [Apple Developer Forums 834952](https://developer.apple.com/forums/thread/834952)
- In iOS 26.4 beta 1 (reported 2026-02-16), the App Store's search tab moved back into the bottom bar instead of a floating circle, and "the Search bar has been moved back to the top of the search tab." — [Manton Reece quoting 9to5Mac, 2026-02-16](https://www.manton.org/2026/02/16/tomac-blogging-about-a-change.html)
- Pre-WWDC26 Bloomberg/Gurman report via 9to5Mac: Apple will put search alongside the other tabs in several apps. This is a leak, not confirmation. — [9to5Mac](https://9to5mac.com/?p=1052059)

### Inferences
- **Safe to build on (primary):** a 4–5 tab bar; `Tab(role: .search)`; minimize on scroll; a bottom accessory that appears only when something is live (`isEnabled:` fits a "running strategy / open order status" strip); `sidebarAdaptable` on iPad.
- **Treat as moving or uncertain:** where the search tab is drawn (trailing-detached versus inline). Apple's own apps changed between 26.0, 26.4 and 27. Using the system role lets the OS decide. `.prominent` exists in the SDK, but its visual behavior and HIG guidance were not documented in primary sources at research time.

### Gaps
- No WWDC26 session page covering `.prominent` or iOS 27 tab bar changes was found or fetched.
- The `TabBarMinimizeBehavior` options list (beyond `.onScrollDown`) was not retrieved.
- Apple has not officially stated that "search moves back inline in iOS 27." The evidence is the 26.4 App Store change, a Bloomberg leak, and beta forum posts.

## Q3. Patterns for "why it moved" explanations and event timelines

### Takeaway
"Why it moved" is now usually an AI summary attached to the **trending or mover list item** (Stocktwits "Why It's Trending," whose AI origin is confirmed), or a **cited research answer** (Google Finance Deep Search with a visible research plan). TradingView handles events as a **filterable news stream** (News Flow with saved feeds and news alerts), not as narrative explanations. No official source confirmed a Yahoo Finance US stock-page "why it's moving" AI summary or a Seeking Alpha equivalent.

### Cited Findings
- Stocktwits says AI powers "Why It's Trending" to filter signal from noise. A scraper schema shows a one-line AI summary next to each trending symbol's rank and score, e.g. NVDA "surging on AI chip demand with new data center orders." — [Lindzon podcast](https://www.howardlindzon.com/p/new-trends-with-friends-episode-from-copilots-to-strip-mining-talent-from-start-ups-inside-ai-s-new); [Apify](https://apify.com/automation-lab/stocktwits-scraper?fpr=data)
- Google Finance Deep Search issues many searches and returns a **cited** response, showing the research plan while it generates. It also offers live earnings tracking. — [Google blog, 2025-11-06](https://blog.google/products/search/new-google-finance-ai-deep-search/)
- TradingView News Flow is described as "your up-to-date hub for financial events." Filters cover markets, corporate activity type and provider. "Create new feed…" saves a filter set. — [TradingView: News Flow, your daily hub](https://in.tradingview.com/support/solutions/43000728828-news-flow-your-daily-hub-for-financial-news/); [TradingView blog: meet News Flow](https://www.tradingview.com/blog/en/customized-real-time-updates-news-flow-46582)
- TradingView real-time news alerts: "when any news matches your selected criteria, you'll receive a notification." — [TradingView blog: real-time news alerts](https://www.tradingview.com/blog/en/real-time-news-alerts-54444/)
- On mobile, TradingView news can be sorted by top stories, asset class and world economy. — [TradingView blog](https://www.tradingview.com/blog/en/customized-real-time-updates-news-flow-46582)
- Yahoo Finance puts a live earnings call audio player **under the chart on the Quote page** (2026). This places an event in time on the chart surface. — [App Store: Yahoo Finance](https://apps.apple.com/us/app/yahoo-finance-stock-market/id328412701)
- eToro (July 2026, from the earlier notes) says its AI agent Tori surfaces the reasons behind price moves. — [eToro press release 2026-07-07](https://www.etoro.com/news-and-analysis/press-releases/etoro-launches-new-app-ai-first-smart-and-social/)

### Inferences
- There are three patterns monticker could combine:
  - an inline one-line AI "why" on mover and alert items (Stocktwits);
  - a cited explanation where the sources are visible (Google Deep Search). This matters for monticker's compliance stance.
  - a filterable, saveable event stream with alert-on-match (TradingView News Flow).

  Monticker's event-centric timeline is closer to TradingView's stream plus chart-attached events (Yahoo earnings player under the chart) than to free-form AI narratives.

### Gaps
- No official source or screenshot was found for a Yahoo Finance US AI "why it's moving / key takeaways" stock-page module, or for a Seeking Alpha AI summary. Search results were spam or SEO pages. This needs a device or web check.
- No official screenshots could be described, because App Store images are placeholders in the fetch tool.
