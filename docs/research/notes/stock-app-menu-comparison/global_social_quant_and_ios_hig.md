# Global, Social, and Quant Investing Apps: Mobile Navigation IA, plus iOS 26/27 Tab Bar Guidance

Research date: 2026-10-18. About 20 search/fetch calls. **Overall reliability warning:** official help centers rarely spell out mobile bottom-tab labels, and App Store screenshots could not be viewed through the tools. Many exact tab lists are therefore **unverified** and recorded under Gaps instead of being guessed. The Apple HIG material is the most reliable part of these notes because it was fetched from Apple's own JSON documentation endpoint.

## Q1. Exact tab labels and order in each app today

### Takeaway
Only Interactive Brokers GlobalTrader has bottom tabs documented in an official source: Watchlist, Portfolio, Explore and Forecasts, with Trade offered as a separate button or ticket rather than as a tab. For eToro, Trading 212, Revolut, Freetrade, TradingView, Stocktwits, Yahoo Finance and Composer, no 2025–2026 primary source lists the bottom tabs. The pattern that does recur is a home/watchlist area, a portfolio area, a discover/explore area (which absorbs markets, copy trading and themes), and a trade action placed outside the tab set.

### Cited Findings
**Interactive Brokers: IBKR GlobalTrader (GT)**
- GT's 2025 iOS release notes tell users to "Click the Forecasts / Watchlist / Portfolio / Explore tab at the bottom of your screen." These four bottom tabs are confirmed. Trade appears as a top tab inside Portfolio, and Transfers sits behind a hamburger icon in the top right. The page was last updated Oct 14, 2025. — [GlobalTrader for iOS 2025 Release Notes](https://ibkrguides.com/releasenotes/gt-ios-2025.htm)
- The same 2025 release notes added a "Tabbed Watchlist", a "Tabbed Portfolio", an "Updated Explore Page", a "Focused Order Ticket" and a consolidated "Transfers" page. — [GT iOS 2025 Release Notes](https://ibkrguides.com/releasenotes/gt-ios-2025.htm)
- An older IBKR Campus lesson (undated, pre-Forecasts) describes four bottom icons: Home, Portfolio, Watchlist and Explore. It also describes a blue **Trade** icon at the bottom right of those screens that opens an order ticket, so trading is an action and not a tab. Inside Portfolio, top tabs are Positions, News, Orders and Trades. — [IBKR Campus: Entering a stock trade](https://www.interactivebrokers.com/campus/trading-lessons/entering-a-stock-trade/)
- **GT redesign on Dec 18, 2025.** It added forecast-contract trading, AI news summaries and a reworked Explore page. — [BusinessWire press release 2025-12-18](https://www.businesswire.com/news/home/20251218345494/en/Interactive-Brokers-Redesigns-IBKR-GlobalTrader-App-for-Simpler-Smarter-Mobile-Trading); [fintech.global 2026-01-02](https://fintech.global/2026/01/02/interactive-brokers-unveils-smarter-ibkr-globaltrader-mobile-trading/)
- Sources conflict on whether Home is still a GT tab. The older Campus lesson lists Home, while the 2025 release notes mention Forecasts, Watchlist, Portfolio and Explore and never name a Home tab. — [Campus](https://www.interactivebrokers.com/campus/trading-lessons/entering-a-stock-trade/) vs [Release notes](https://ibkrguides.com/releasenotes/gt-ios-2025.htm)

**Interactive Brokers: IBKR Mobile (flagship)**
- IBKR Mobile has a new Homepage that combines account, position and market updates and includes a "For You" notifications area. It was rolled out gradually on Android first, with a customizable layout promised later. The article is undated. — [FinanceFeeds](https://financefeeds.com/interactive-brokers-introduces-new-homepage-ibkr-mobile-app/)
- Trade functions were consolidated into a "Trade" toolbox opened from a bottom-bar icon, and users can reorder the tabs inside Portfolio. The article is undated. — [FXNewsGroup](https://fxnewsgroup.com/forex-news/platforms/interactive-brokers-adds-more-widgets-to-mobile-app/)

**eToro**
- **New app launched Jul 7, 2026,** billed as "AI-first, smart, and social." It brings clearer portfolio views, richer asset pages, advanced charts and more personalization. The AI agent "Tori" is described as "etoro's proactive AI agent" that surfaces portfolio insights, market signals and the reasons behind price moves. Users can also "copy AI agents." — [eToro press release, 2026-07-07](https://www.etoro.com/news-and-analysis/press-releases/etoro-launches-new-app-ai-first-smart-and-social/); [Funds Society](https://www.fundssociety.com/en/news/markets/etoro-launches-new-app-ai-as-the-core-social-and-intelligent/)
- eToro merged its Trade Markets, Copy People and Smart Portfolios pages into a single **"Discover"** page with search at the top. The article is undated. — [FXNewsGroup: eToro introduces new Discover page](https://fxnewsgroup.com/forex-news/platforms/etoro-introduces-new-discover-page/)
- eToro's Watchlist is "your hub for tracking all of the people and assets you care about." It tracks both people and assets, and the people and assets you follow personalize your news feed. — [eToro Academy: Getting started part 2](https://www.etoro.com/academy/videos/getting-started-on-etoro-part-2/)
- The web Home screen has a ticker bar, a "Big Movers" widget covering the watchlist and portfolio, and an interactive portfolio-change chart. — [FXNewsGroup: new Web Home Screen](https://fxnewsgroup.com/forex-news/platforms/etoro-unveils-new-web-home-screen/)
- The eToro site lists a "$100K Demo Account" (virtual portfolio) and CopyTrader as signature products. — [eToro press release page nav](https://www.etoro.com/news-and-analysis/press-releases/etoro-launches-new-app-ai-first-smart-and-social/)

**Trading 212**
- **Mid-December 2025 redesign, reverted within about 10 days.** The redesign used a bubble-style card layout with pull-up menus and hid portfolio holdings behind a search bar. It removed the axis numbers from the portfolio graph and ignored the user's color theme setting. After user backlash, Trading 212 said the previous layout would be restored in a January update. — [Coinspectator, 2025-12-29](https://coinspectator.com/mainstream/2025/12/29/trading-212-reverses-interface-redesign-after-users-warn-theyll-switch-brokers-its-like-youre-intentionally-trying-to-anger-us/); [T212 Community thread](https://community.trading212.com/t/new-layout-ruined-t212/89220)

**Revolut**
- Revolut 7.0 split the app into "home" and "wealth" sections. Wealth gathered stocks, crypto, commodities and Vaults. Revolut 8.0 added a smart search bar to the top of every main tab. Revolut 10 (Oct 2023) was the largest overhaul and made investing tools reachable from Home. — [FinTech News SG](https://fintechnews.sg/78771/digital-banking-news-singapore/revolut-launches-its-biggest-update-yet-for-over-35-million-users/); [Finextra (v8)](https://www.finextra.com/pressarticle/87342/revolut-ships-version-80-of-financial-superapp)
- One forum user (anecdotal) located a feature in "the second tab in the bottom 'wealth'". — [boards.ie](https://www.boards.ie/discussion/comment/113490724/)

**Freetrade**
- Freetrade's iPad app (2021, dated) uses a sidebar to choose tabs and switch accounts. — [Freetrade blog: Introducing Freetrade for iPad](https://freetrade.io/blog/introducing-freetrade-for-ipad)

**TradingView mobile**
- A third-party broker walkthrough lists bottom tabs Watchlist, Ideas, News and Profile, plus a Chart icon for the main chart and a "+" for adding symbols. This is a third-party guide and is unverified. — [Optimus Futures](https://optimusfutures.com/blog/master-the-tradingview-mobile-dashboard-in-seconds-tradingview-mobiletrading-trading/)
- An older TradingView blog post (iOS 14 era) mentions a "menu" tab for the profile and settings. — [TradingView blog](https://www.tradingview.com/blog/en/you-ve-improved-our-mobile-apps-25189)

**Stocktwits**
- The only tab list found is undated and probably old: Trending, Watch List, Streams and Settings. — [id.nl](https://id.nl/huis-en-entertainment/computer-en-gaming/software/consumeren-en-delen-van-beursfeiten-en-meningen-55991)

**Yahoo Finance**
- Version 2.2 (older, undated) added a tab bar with Home (tracked stocks), News and Markets. — [9to5Mac](https://9to5mac.com/guides/yahoo-finance/)

**Exante (bonus EU example)**
- **Aug 19, 2026 redesign:** orders and transactions were merged into a single "Activity" module. — [fintech.global 2026-08-19](https://fintech.global/2026/08/19/exantes-app-redesign-puts-control-back-in-investors-hands/)

### Inferences
- GT reached its current shape by dropping a dedicated Home tab and adding an asset-class tab (Forecasts) while keeping Trade off the tab bar. That agrees with the HIG rule "navigation, not actions" (see Q4).
- eToro's Discover consolidation shows a pattern: copy trading (people), Smart Portfolios and markets are all browsed in one discovery surface, not given separate tabs. The watchlist mixing people and assets is eToro's distinctive social IA choice.
- Trading 212's reverted redesign is a strong cautionary data point. Users of active investing apps penalize redesigns that add taps or hide holdings behind search, and they react within days.

### Gaps
- No primary 2025–2026 source verified the current **bottom tab labels or order** for eToro (including the July 2026 new app), Trading 212, Revolut (whether it is labelled "Invest", "Wealth" or something else), Freetrade, TradingView, Stocktwits, Yahoo Finance or Composer. App Store screenshots could not be read with the available tools. Someone should check them on a device or in App Store screenshots.
- Whether Trading 212's January 2026 rollback actually shipped was not confirmed.
- No dated source covers IBKR Mobile's current bottom-tab set.
- Japanese and Hong Kong examples (SBI証券, 楽天証券 iSPEED, Futu/moomoo) were not researched because of the tool-call budget.

## Q2. Where social/copy trading and paper trading surface in navigation

### Takeaway
Social and copy trading in eToro lives inside **Discover** (people sit next to markets and Smart Portfolios) and inside the **Watchlist** (people are followed like assets). As of the July 2026 app, it extends to copying AI agents. Paper/demo trading is a mode or account switch (eToro's $100K demo account, TradingView's Trading Panel on the chart). None of the sources describe it as a bottom tab.

### Cited Findings
- eToro Discover merges "Copy People" with markets and Smart Portfolios. — [FXNewsGroup](https://fxnewsgroup.com/forex-news/platforms/etoro-introduces-new-discover-page/)
- On eToro, followed people and assets share the Watchlist and personalize the news feed. — [eToro Academy](https://www.etoro.com/academy/videos/getting-started-on-etoro-part-2/)
- eToro promotes CopyTrader, a "$100K Demo Account" and copying AI agents (2026). — [eToro press release 2026-07-07](https://www.etoro.com/news-and-analysis/press-releases/etoro-launches-new-app-ai-first-smart-and-social/)
- TradingView paper trading starts from the "Trading Panel" button at the bottom of the chart. This comes from a web/desktop description, and mobile parity is unverified. — [Optimus Futures / TradingView guidance](https://optimusfutures.com/blog/master-the-tradingview-mobile-dashboard-in-seconds-tradingview-mobiletrading-trading/)
- TradingView's social layer has an "Ideas" bottom tab, according to a third-party guide (unverified). — [Optimus Futures](https://optimusfutures.com/blog/master-the-tradingview-mobile-dashboard-in-seconds-tradingview-mobiletrading-trading/)

### Inferences
- Paper trading works best as a context or account toggle that applies to every tab, not as a destination of its own. This matches how both eToro and TradingView treat it.
- Social content appears in two places: a feed or ideas surface (TradingView Ideas, Stocktwits Streams) and objects attached to assets (people inside eToro's watchlist).

### Gaps
- No source says where the eToro feed sits in the 2026 app's tab bar, or how the demo/real switch is triggered on mobile.
- Stocktwits' current 2025–2026 mobile IA was not verified.

## Q3. How quant/no-code apps (Composer, TradingView) structure strategy creation, backtesting and live running

### Takeaway
Composer is built around the "symphony" object. Users build it with a no-code block editor or, since Oct 2025, with an AI assistant that turns plain English into a strategy. They backtest it with $100K default capital and realistic fees. They then "invest" in it, and Alpaca executes the trades automatically. No source documented the mobile tab layout. TradingView's strategy and backtest features (Pine/Strategy Tester) are mainly desktop. On mobile, paper trading runs through the chart's Trading Panel.

### Cited Findings
- A Composer backtest is "the basic way for any user to evaluate the logic and performance of any Composer symphony." The default initial investment is $100,000, and the backtest includes the Trading Pass fee, regulatory fees and slippage. — [Composer Help: How do backtests work](https://help.composer.trade/article/215-7-how-do-backtests-work-in-composer)
- Composer executes trades through Alpaca, and the App Store listing says users can start with about $50. — [Composer Help](https://help.composer.trade/article/215-7-how-do-backtests-work-in-composer); [Benzinga review](https://benzinga.com/money/composer-review)
- The no-code editor chains conditional logic blocks. An AI assistant launched in October 2025 turns plain English into a backtested symphony. Aggregators give conflicting prices ($29/month Pro versus a Trading Pass of roughly $24–40/month). — [stork.ai](https://www.stork.ai/tools/composer); [tooliverse 2026](https://tooliverse.ai/tools/composer)

### Inferences
- The Composer lifecycle (create → backtest → invest/deploy → monitor) treats the strategy as the main object. A monticker Quant Lab could copy this with a "Strategy" tab whose detail screen shows backtest and live-performance tabs, so that create, test and run are not split into separate bottom tabs.

### Gaps
- Composer's mobile tab names were not verified (the help article on creating strategies returned 404).
- How TradingView mobile handles the Strategy Tester was not verified.

## Q4. Apple HIG and WWDC 2025–2026 guidance: tab bars, search tab, bottom accessory, number of tabs (iOS 26 and iOS 27)

### Takeaway
iOS 26 (Liquid Glass) made the tab bar a floating glass capsule. It added an optional search tab at the trailing end, minimize-on-scroll, and a bottom accessory (like Music's MiniPlayer). It also added `sidebarAdaptable` for iPad. The HIG still says to use the tab bar for navigation only, to keep it visible, and to aim for five or fewer tabs. Changes for iOS 27 (WWDC26, June 2026): a new `.prominent` tab role (trailing-separated like search), a UIKit `prominentTabIdentifier`, and, according to reports, system apps putting search back among the regular tabs.

### Cited Findings
**HIG "Tab bars" (Apple, current; change log Jun 8 2026 / Dec 16 2025 / Jul 28 2025)**
- Floating: "A tab bar floats above content at the bottom of the screen. Its items rest on a Liquid Glass background that allows content beneath to peek through." — [Apple HIG: Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- Search: "A tab bar can include a dedicated search tab at the trailing end." — [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- Minimize: "For tab bars with an attached accessory, like the MiniPlayer in Music, you can choose to minimize the tab bar and move the accessory inline with it when a person scrolls down. A person can exit the minimized state by tapping a tab or scrolling to the top of the view." The APIs are `TabBarMinimizeBehavior` and `UITabBarController.MinimizeBehavior`. — [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- Actions: "Use a tab bar to support navigation, not to provide actions." and "If you need to provide controls that act on elements in the current view, use a toolbar instead." — [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- Visibility: "Make sure the tab bar is visible when people navigate to different sections of your app." Modals are the exception. — [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- Count: "Use the appropriate number of tabs required…" and "it's generally easier to navigate among fewer tabs." For customizable tabs, "aim for a default list of five or fewer to preserve continuity between compact and regular view sizes." Overflow becomes a "More" tab, which "makes it harder for people to reach and notice content." — [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- iPadOS: the tab bar sits near the top and can be fixed or convertible to a sidebar (`TabViewStyle.sidebarAdaptable`; also `.tabBarOnly`). "To present a sidebar without the option to convert it to a tab bar, use a navigation split view." — [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- Change log: Jul 28 2025 "Added guidance for Liquid Glass"; Dec 16 2025 "Updated guidance for Liquid Glass"; Jun 8 2026 "Updated terminology and art". — [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/tab-bars)

**iOS 26 APIs (developer sources)**
- Recompiling with Xcode 26 applies the Liquid Glass tab bar automatically. `Tab(role: .search)` separates the search tab from the other items and morphs it into a search field when selected. `.tabBarMinimizeBehavior(.onScrollDown)` collapses the bar on scroll. `.tabViewBottomAccessory { }` places a capsule glass view above the tab bar, and that view slides inline when the bar minimizes. — [Donny Wals](https://www.donnywals.com/exploring-tab-bars-on-ios-26-with-liquid-glass/); [Create with Swift: bottom accessory](https://www.createwithswift.com/enhancing-the-tab-bar-with-a-bottom-accessory/); [Create with Swift: collapse on scroll](https://www.createwithswift.com/making-the-tab-bar-collapse-while-scrolling/); [Hacking with Swift](https://www.hackingwithswift.com/quick-start/swiftui/how-to-add-a-tabview-accessory)
- In a search tab whose content uses `.searchable`, the search field is placed at the bottom. — [classmethod (JP)](https://dev.classmethod.jp/articles/ios-26-liquid-glass-tab-bar/)
- In early betas, the accessory's placement detection (`TabViewBottomAccessoryPlacement`) was unreliable. — [Create with Swift](https://www.createwithswift.com/enhancing-the-tab-bar-with-a-bottom-accessory/)

**iOS 26.4 / iOS 27 (WWDC26, June 2026)**
- The new SwiftUI `.prominent` tab role creates "trailing-separated tabs, similar to search", e.g. `Tab("create", systemImage: "pencil", role: .prominent)`. — [Swift with Majid, 2026-06-08](https://swiftwithmajid.com/2026/06/08/what-is-new-in-swiftui-after-wwdc26/)
- UIKit adds `prominentTabIdentifier` to mark one tab as visually prominent, plus controls for choosing tab bar versus sidebar presentation. — [Swiftjectivec: iOS 27 UIKit additions](https://swiftjectivec.com/iOS-27-Notable-UIKit-Additions/)
- Gurman (Bloomberg, via 9to5Mac, pre-release report) says Apple is changing several apps so the search tab sits alongside the other tabs instead of standing apart. 9to5Mac called the iOS 26 split search button a regression. — [9to5Mac](https://9to5mac.com/?p=1052059)
- The iOS 26.4 beta moved the App Store's search tab back into the bottom bar and put the search field back at the top. — [Manton Reece quoting 9to5Mac, 2026-02-16](https://www.manton.org/2026/02/16/tomac-blogging-about-a-change.html)
- A beta developer forum report says an iOS 27 search tab using `.prominent` doesn't morph or collapse the other tabs the way system apps do, which suggests an SDK regression (unconfirmed). — [Apple Developer Forums 834952](https://developer.apple.com/forums/thread/834952)
- An iOS 27 beta report says `tabBarController(_:shouldSelect:)` is no longer called inside a SwiftUI TabView and that `tabBarController(_:shouldSelectTab:)` is the replacement (unofficial). — [Apple Developer Forums 831295](https://developer.apple.com/forums/thread/831295)

**Apple Stocks app**
- No source specific to the Stocks app's iOS 26/27 redesign was found. Apple's iPhone User Guide does include an iOS 27 version, but the article body didn't load. — [Apple Support: Check stocks on iPhone](https://support.apple.com/guide/iphone/check-stocks-iph1ac0b1bc/ios)
- Context: Apple moved its own apps to Liquid Glass gradually. The Apple Store app kept its four tabs and changed only its appearance. — [BGR](https://www.bgr.com/2026634/apple-store-app-liquid-glass-makeover-holiday-shopping/)

### Inferences
- For a finance app: keep 4–5 navigation tabs. Put Trade/Order in a toolbar or a `.prominent` tab, not in a regular tab, because the HIG says "not actions". Note that `.prominent` is a navigation tab and not a floating action button, so if Trade becomes `.prominent` it should open a trade *destination*. A live-portfolio ticker, an active-order status strip or a running-strategy status is a natural fit for `tabViewBottomAccessory`, the same pattern as Music's MiniPlayer. Minimize-on-scroll suits long news and event feeds.
- Search placement is changing. iOS 26 separated search at the trailing end, while 26.4 and 27 system apps are moving it back among the tabs. Building on `Tab(role: .search)` lets the system decide placement.
- On iPad, use `sidebarAdaptable` so that Quant Lab, Strategy Market and the other secondary sections can sit in the sidebar without growing the phone tab bar past five.

### Gaps
- The official WWDC26 session pages and Apple documentation for `Tab(role: .prominent)` and the iOS 27 search tab behavior were not fetched directly, so the iOS 27 details rely on secondary sources and a Bloomberg leak.
- The Apple Stocks app's actual iOS 26/27 navigation (watchlist sidebar, search, news) was not verified.
