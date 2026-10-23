# US Retail Stock Trading Apps (iOS): Bottom Tab Bar / Information Architecture, 2025–2026

Research date: 2026-10-18. Method: web search + fetch of official pages (help centers, release notes, newsrooms) and press. Many apps do NOT document their exact tab labels publicly; where a label could not be verified from a citable source it is flagged as such. Nothing here was verified by inspecting the live iOS apps.

## Q1. What are the exact tab labels and order today on iOS?

### Takeaway
Only moomoo (official guide) and Fidelity (official release notes) have well-documented current tab structures. Webull, Schwab, and E*TRADE have partial or older third-party/official descriptions. Robinhood, Public, SoFi, and Cash App have no citable current tab-label documentation found. The common pattern is 5 tabs (moomoo uses 6), with a "More/Menu/Me" tab at the far right.

### Cited Findings

**moomoo (Futu), US app: 6 tabs (official)**
- Order: **Watchlist** (default landing page; heart icon to add; shows portfolio performance after deposit) → **Markets** (exchange data; US tab holds stock screener, heat map, featured lists, market monitor) → **Accounts** (assets, order history, transfers, FX, withdrawals) → **News** (24h news, Bloomberg/Dow Jones/Reuters) → **Discover** (subtabs: **Moo** community, **Moomoo Learn**, **Papertrade**) → **Me** (points, settings, help center, feedback). Source: [moomoo US Features Overview](https://www.moomoo.com/us/learn/overview)
- Regional variation: Singapore guide also says six bottom tabs with Watchlist as default. Malaysia/US guides list News between Accounts and Discover. Labels and count vary by region and version. Source: [moomoo SG overview](https://www.moomoo.com/sg/learn/overview), [moomoo MY overview](https://www.moomoo.com/my/learn/overview)

**Fidelity Investments app: two navigation modes (official release notes)**
- Fidelity offers a **Standard** navigation layout (positions and net worth one tap away) and a **Classic** layout (market info and Watch Lists within reach). Source: [Fidelity app page](https://www.fidelity.com/mobile/fidelity-investments-app)
- Sep 2025: in **Fidelity Trader+** mode, "Portfolio," "Watchlist," and "Markets" moved into the bottom nav. Oct 2025: Trader+ Portfolio tab shows all tradeable account balances; trade ticket reachable from the bottom navigation in Trader+ mode. Jan 2026: Trader+ mode is toggled in General settings. Source: [Fidelity What's New](https://fidelity.com/mobile/whats-new-fidelity-app)
- Paths named in release notes imply Standard-mode top-level destinations including **Investing** ("Investing > Positions tab", May 2026), **Transact** ("Transact > Trade", Sep 2025; "Transact > Activity & Orders", May 2026), **Discover** ("Discover > Collections > Crypto", May 2026), **Markets** ("Go to the Markets tab" for Morning Market Briefing, Aug 2026), **Planning** tab with Spending (Sep 2025), **Profile** (Security, Jan 2026), plus a customizable **Home** screen (Jul 2025: hide/reorder/add sections). Source: [Fidelity What's New](https://fidelity.com/mobile/whats-new-fidelity-app)
- A designer portfolio case study says Discover is one of five primary navigation tabs (secondary source, undated). Source: [zcool case study](https://www.zcool.com.cn/work/ZNzE2NzE3MDQ=.html)

**Webull: ~5 tabs (partial; dated third-party)**
- A Singapore review (circa 2024) lists five tabs: **Watchlists, Feeds, Open Account, Markets, Menu**, opening on Watchlists by default; the middle tab changes based on whether you have an account. Source: [MoneySmart SG review](https://blog.moneysmart.sg/invest/best-investment-brokerage-singapore-webull-review/)
- Webull 11.0 (official learn page; release date not stated): search icon moved from top-right to a **search bar pinned at the bottom of the Markets page**, usable from Watchlist, Markets, or Feeds; **Feeds** redesigned to combine Webull Community, news, and Learn Centre; empty-Watchlist "quick pickup" of five suggested stocks; image search (upload screenshot of tickers). Source: [Welcome to Webull 11.0 (AU)](https://www.webull.com.au/learn/courseware/kkChcs/Welcome-to-Webull-11-0)

**Charles Schwab (thinkorswim mobile): 5 customizable tabs (official, undated)**
- Five primary tabs at the bottom: **Overview, Watchlist, Trade (Quick Trade), Positions, More**; More holds additional tools and lets users customize the tab bar. Guide date not shown. Source: [Schwab: 9 Steps to Master thinkorswim mobile for iPhone](https://www.schwab.com/learn/story/9-steps-to-master-thinkorswim-mobile-iphone)
- Dec 15, 2025 enhancements (tax lots, account history ledger, news-source filtering) did not mention navigation changes. Sep 2025 App Store update added Account History reached via the navigation menu. Source: [Schwab press release, Dec 15 2025](https://pressroom.aboutschwab.com/press-releases/press-release/2025/Schwab-Announces-Latest-Enhancements-to-Trading-Experience/default.aspx)

**E*TRADE (Morgan Stanley): 5 tabs (third-party review)**
- Main E*TRADE app bottom menu: **Accounts, Watch Lists, Trade, Markets, Menu** (2026 review; only source). Source: [Brokerage-Review E*TRADE app](https://www.brokerage-review.com/online-trading/mobile/etrade-app.aspx)
- Last documented menu redesign was Feb 2022 (iOS then Android). Source: [Business Wire, Feb 3 2022](https://www.businesswire.com/news/home/20220203005946/en)
- Power E*TRADE app: single trade ticket for stocks, ETFs, options, futures; no nav description. Source: [E*TRADE Mobile](https://www.etrade.com/mobile)

**Robinhood: current tab labels NOT verified**
- Last official navigation announcement found is Oct 16, 2018 (bottom tab bar with shortcuts to portfolio, watchlist, search, statements). Source: [Robinhood newsroom, Oct 2018](https://robinhood.com/us/en/newsroom/a-new-way-to-navigate-robinhood/)
- A 2021 student critique listed five tabs: Dashboard, Cash Management, Search, Messages, Profile (old, unofficial). Source: [Pratt IXD critique 2021](https://ixd.prattsi.org/2021/02/design-critique-robinhood-app-ios-app/)
- Unofficial support guides place a person-shaped **Account** icon at bottom-right (low-quality sources). Source: [Robinhood newsroom 2018](https://robinhood.com/us/en/newsroom/a-new-way-to-navigate-robinhood/) (official baseline only)
- iOS App Store release notes for every 2026 version (2026.23–2026.41) say only "Bug fixes and improvements", so they give no navigation detail. Source: [App Store listing](https://apps.apple.com/us/app/robinhood-trading-investing/id938003185)

**Public.com: tab labels NOT verified**
- Help center: in the mobile app, a menu icon sits at the top-left of the **portfolio page**, and Settings is reached from it. Web version uses a left sidebar (portfolio dashboard, inbox) and top search bar. Source: [Public help: app version](https://help.public.com/en/articles/12110586-how-do-i-find-what-app-version-i-m-on), [Public help: web experience](https://help.public.com/en/articles/5724380-explore-the-new-investing-experience-on-the-web)

**SoFi Invest: tab labels NOT verified**
- SoFi app Home screen redesign was presented as a recent launch at SF Design Week (June 2025), described as an incremental overhaul of the most-visited page. Source: [SF Design Week event](https://sfdesignweek.org/?p=54212)
- SoFi Invest refresh (undated blog): dark mode plus a **quick menu on the Invest homepage** for common actions incl. buy/sell. Source: [SoFi blog: SoFi Invest Debuts A New Look](https://www.sofi.com/blog/sofi-invest-refresh/)

**Cash App Investing: tab labels NOT verified**
- Bankrate (Mar 2025): banking, spending, and investing in one app; you reach investing by "swiping over to the investing side"; stocks/ETFs from $1, plus Bitcoin buy/sell/transfer. Source: [Bankrate Cash App review](https://www.bankrate.com/investing/cash-app-review)
- Cash App introduced a design language refresh and **Moneybot** AI assistant (date unclear in sources). Source: [Abduzeedo](https://www.abduzeedo.com/cash-apps-new-design-language-and-ai-assistant-moneybot), [Digital Transactions](https://www.digitaltransactions.net/cash-app-intros-its-moneybot-ai-tool-amid-a-range-of-new-features/)

### Inferences
- Active-trader apps (moomoo, Webull, thinkorswim, E*TRADE, Fidelity Trader+) converge on **Watchlist / Markets / Trade-or-Account / (News|Feeds) / More** tab structures. moomoo is the outlier, making News and Discover (community + paper trading) top-level tabs.
- Webull and moomoo open on **Watchlist**, not account value. Schwab opens on Overview (account). E*TRADE's first tab is Accounts.
- Fidelity is the clearest case of **dual IA** (Standard for long-term investors vs Trader+ for active traders), toggled in settings.

### Gaps
- Exact current iOS tab labels/order for Robinhood, Public, SoFi, Cash App: no official or reliable source found. Would require App Store screenshots or hands-on inspection.
- Webull current US iOS tab labels (post-11.0) are unverified; the five-tab list is from an older Singapore review.
- Schwab guide is undated; E*TRADE relies on one third-party review.

## Q2. Which apps put a feed/news/AI digest front and center vs account value?

### Takeaway
Robinhood is moving fastest toward an AI-digest-first home (Portfolio Digests on the investing home screen, Cortex as "the interface for all of Robinhood", 2026), plus a social feed. Webull and moomoo push community/news via dedicated tabs but land on Watchlist. Fidelity, Schwab, and E*TRADE remain account/portfolio-first, with briefings added inside tabs.

### Cited Findings
- **Robinhood Cortex Digests**: first Cortex feature; generative AI summarizes "why a particular stock may be moving" from news, analyst reports, technicals, and proprietary data; located on the **stock detail page**. Announced at the "Lost City of Gold" event (March 2025), rolled out in the US in summer 2025, UK on Aug 19, 2025. Source: [Robinhood newsroom: Digests UK, Aug 19 2025](https://robinhood.com/us/en/newsroom/digests-by-robinhood-cortex-uk), [tech.eu](https://tech.eu/2025/08/19/robinhood-launches-stock-picking-ai-summaries/)
- **Robinhood Portfolio Digests** (announced Dec 16, 2025 at the "YES/NO" event): AI portfolio-level summaries of top return drivers, upcoming events (earnings, splits), and benchmark comparison; appear **on the investing home screen**; refresh several times a day or on breaking news; Gold subscribers, early 2026. Source: [Robinhood newsroom: YES/NO event, Dec 16 2025](https://robinhood.com/us/en/newsroom/robinhood-presents-yes-no-event)
- **Next-gen Robinhood Cortex**: "embedded throughout the app" as "the interface for all of Robinhood"; plain-English trading of equities/crypto, research, event contracts, account settings; Q1 2026 for Gold. Source: [Robinhood newsroom, Dec 16 2025](https://robinhood.com/us/en/newsroom/robinhood-presents-yes-no-event)
- **Robinhood Social**: announced at HOOD Summit (Sep 10, 2025) as an in-app trading community with verified trades and profiles. Source: [Robinhood newsroom: HOOD Summit 2025](https://robinhood.com/newsroom/hood-summit-2025-news/). Beta started March 2026 (about 1,000 users, then 10,000); users can comment, like, and place manual trades from the feed. Source: [Fortune, Mar 18 2026](https://www.fortune.com/2026/03/18/robinhood-rolls-out-long-awaited-social-feature-to-let-users-track-and-copy-other-traders), [CFOtech](https://cfotech.com.au/story/robinhood-tests-social-trading-beta-with-verified-users). Later opened to all eligible US customers, with conflicting dates (one report cites a Sep 29 HOOD Summit announcement). Source: [Finance Magnates](https://www.financemagnates.com/forex/robinhood-lets-all-eligible-us-customers-share-their-trades-and-portfolios/)
- Robinhood newsfeed with Barron's/Reuters/WSJ content (a Feb 2026-dated article; low-quality source). Source: [Apptunix](https://apptunix.com/blog/robinhood-app-newsfeed-redesigned/)
- **Public "Key Moments"**: AI-generated summaries of stock price moves shown on the asset's performance chart. Also an Earnings Hub with AI earnings recaps and an Income Hub. Source: [Public features](https://public.com/features)
- **Public Alpha** (May 2023, GPT-4): chat on any stock/asset page. Source: [Crowdfund Insider, May 2023](https://www.crowdfundinsider.com/2023/05/207353-investing-platform-public-launches-alpha-powered-by-openais-gpt-4-for-investment-research/). Alpha was later also released as a standalone app (date unclear). Source: [Tearsheet](https://tearsheet.co/artificial-intelligence/the-unbundling-of-fintech-when-fintech-apps-spawn-new-offerings/)
- **Public "Agentic Brokerage"** (Nov 17, 2025): three pillars: Research (AI assistant that proactively scans markets and analyzes earnings calls), Creation (Generated Assets), and Action (AI agents for event-conditional rebalancing, cash sweeps, and strategy automation, planned early 2026). No in-app location given. Source: [FNG, Nov 2025](https://fxnewsgroup.com/forex-news/retail-forex/public-introduces-new-phase-of-its-ai-vision-agentic-brokerage/)
- **Webull Feeds** tab merges community, news, and Learn. Source: [Webull 11.0](https://www.webull.com.au/learn/courseware/kkChcs/Welcome-to-Webull-11-0)
- **moomoo**: dedicated **News** tab plus **Discover > Moo** community (20M+ users claimed). Source: [moomoo US overview](https://www.moomoo.com/us/learn/overview), [moomoo SG](https://www.moomoo.com/sg/learn/overview)
- **Fidelity Morning Market Briefing** (Aug 2026), located in the Markets tab, not Home. Fidelity Assistant (AI chat) got feedback thumbs (Aug 2025). Source: [Fidelity What's New](https://fidelity.com/mobile/whats-new-fidelity-app)
- **Cash App Moneybot** AI assistant described as groundwork for future interfaces. Source: [Abduzeedo](https://www.abduzeedo.com/cash-apps-new-design-language-and-ai-assistant-moneybot)

### Inferences
- "Why is it moving" explanations are now table stakes on the **stock detail page** (Robinhood Digests, Public Key Moments on the chart). The 2026 frontier is **portfolio-level** event digests on the home screen (Robinhood Portfolio Digests) and agentic action (Public).
- Incumbents (Fidelity, Schwab, E*TRADE) keep account value first and tuck briefings and news into the Markets tab.

### Gaps
- Whether Robinhood's home screen today leads with the portfolio chart or with Portfolio Digests: Digests appear "on the investing home screen", but relative placement is unverified.
- No source confirmed whether Robinhood Social has its own bottom tab.

## Q3. How do they handle multiple asset classes in navigation?

### Takeaway
Two patterns. (a) **Account/product switching** within a single app (Robinhood: investing, crypto, retirement, prediction markets hub, up to 10 investing accounts; Cash App: swipe between money and investing). (b) **Unified watchlist/markets with asset filters** (moomoo, Webull, thinkorswim, Fidelity Discover collections). Specific Robinhood navigation mechanics are not verified.

### Cited Findings
- Robinhood offers stocks, options, 50+ crypto, Robinhood Gold, a "**Robinhood Prediction Markets Hub**" (sports, politics, economics, culture), and Robinhood Strategies (managed portfolios via an RIA). Source: [App Store listing](https://apps.apple.com/us/app/robinhood-trading-investing/id938003185)
- Dec 2025: prediction markets added a live sports contract hub and sports event detail pages. Source: [Robinhood newsroom, Dec 16 2025](https://robinhood.com/us/en/newsroom/robinhood-presents-yes-no-event)
- HOOD Summit 2025: multiple investing accounts (up to 10 self-directed individual accounts), futures on Legend, short selling, overnight index options. Source: [Robinhood newsroom: HOOD Summit 2025](https://robinhood.com/newsroom/hood-summit-2025-news/), [Crowdfund Insider](https://www.crowdfundinsider.com/?p=250653)
- Fidelity places crypto education at "Discover > Collections > Crypto" (May 2026) and Youth Accounts get a dedicated streamlined-navigation view (Oct 2025). Source: [Fidelity What's New](https://fidelity.com/mobile/whats-new-fidelity-app)
- Power E*TRADE: one trade ticket for stocks, ETFs, options, futures. Source: [E*TRADE Mobile](https://www.etrade.com/mobile)
- Cash App: investing reached by swiping to the investing side; Bitcoin is a separate function. Source: [Bankrate](https://www.bankrate.com/investing/cash-app-review)
- Robinhood's banking/credit features are under the Home tab's Credit section in its Banking experience, with a separate **Family** tab (help article, about 559 days old). Source: [Robinhood Banking support](https://robinhood.com/us/en/support/articles/robinhood-banking/)

### Inferences
- Robinhood's trajectory (Gold, Banking, Strategies, prediction markets, multiple accounts) pushes toward a super-app with account switching. The Cortex-as-interface plan (2026) suggests AI search/command may become the cross-asset navigation layer.

### Gaps
- How Robinhood exposes crypto, retirement, and prediction markets in the iOS chrome (top switcher vs tab) is not documented in citable sources.

## Q4. Where do paper trading, screeners, strategy builders live?

### Takeaway
Paper trading is a top-level or near-top-level feature only for moomoo (Discover > Papertrade) and Webull (formerly under the Open Account tab). Screeners sit under Markets (moomoo). Strategy-building is moving to AI: Public Generated Assets (prompt to backtested index), Robinhood Cortex scripting/scanners and simulated returns on Legend (desktop).

### Cited Findings
- moomoo: Papertrade under **Discover**; stock screener, heat map, and market monitor under **Markets > US**. Source: [moomoo US overview](https://www.moomoo.com/us/learn/overview)
- Webull: paper trading under the **Open Account** tab ("Start Paper Trading") per a circa-2024 SG review; at test time it lacked fractional shares and options. Source: [MoneySmart SG](https://blog.moneysmart.sg/invest/best-investment-brokerage-singapore-webull-review/). The current US location is unverified.
- **Robinhood Legend** (desktop/web platform for active traders, launched Oct 2024). Source: [Finance Magnates](https://www.financemagnates.com/forex/robinhood-lets-all-eligible-us-customers-share-their-trades-and-portfolios/), [Robinhood support: Get started with Legend](https://robinhood.com/us/en/support/articles/get-started-with-robinhood-legend/). HOOD Summit 2025 added Cortex scripting/scanners (AI custom indicators and scans) and simulated returns on Legend. Source: [Robinhood newsroom: HOOD Summit 2025](https://robinhood.com/newsroom/hood-summit-2025-news/)
- **Public Generated Assets** (May 14, 2025): a text prompt builds a custom equity index, backtested and benchmarked vs the S&P 500; 2,500+ built in the month before Nov 2025. Source: [Finance Magnates](https://www.financemagnates.com/forex/public-debuts-ai-powered-index-generator-benchmarking-sp-500/), [FNG Nov 2025](https://fxnewsgroup.com/forex-news/retail-forex/public-introduces-new-phase-of-its-ai-vision-agentic-brokerage/)
- Fidelity Trader+: advanced charting, heatmap Positions view, immersive Positions view hiding top/bottom nav (Jul 2026), options chain centered on the at-the-money strike. Source: [Fidelity What's New](https://fidelity.com/mobile/whats-new-fidelity-app)

### Inferences
- In mobile apps, strategy building is offered as AI-generated thematic portfolios (Public) rather than rule-based backtest builders. Rule-based scanners and scripting remain desktop-first (Robinhood Legend, thinkorswim desktop).

### Gaps
- No source found for paper trading in Robinhood, Public, SoFi, Fidelity, or E*TRADE mobile apps. thinkorswim paperMoney mobile location also unverified.
- moomoo options strategy builder location is not covered in the overview page.

## Q5. Any adoption of iOS 26 Liquid Glass tab bar design?

### Takeaway
Fidelity is the only app with an explicit, dated Liquid Glass announcement (July 2026, iOS). No Robinhood, Public, Webull, moomoo, Schwab, E*TRADE, SoFi, or Cash App source confirmed Liquid Glass or a separate search tab.

### Cited Findings
- Fidelity July 2026 release notes: "Introducing Apple's liquid glass design" (iOS only). Same month: immersive Trader+ Positions view removes top/bottom navigation. Source: [Fidelity What's New](https://fidelity.com/mobile/whats-new-fidelity-app)
- Apps with a standard UITabBar get the Liquid Glass look automatically when recompiled with Xcode 26; tab bars shrink on scroll and expand on scroll-up. Source: [Donny Wals: Exploring tab bars on iOS 26](https://www.donnywals.com/exploring-tab-bars-on-ios-26-with-liquid-glass/), [Apple press release via Nasdaq, Jun 9 2025](https://www.nasdaq.com/press-release/apple-introduces-delightful-and-elegant-new-software-design-2025-06-09)
- Webull already moved search to a bottom-pinned search bar on Markets (11.0), similar to iOS 26's bottom search pattern, but not tied to Liquid Glass in the source. Source: [Webull 11.0](https://www.webull.com.au/learn/courseware/kkChcs/Welcome-to-Webull-11-0)

### Inferences
- Apps using native tab bars likely show Liquid Glass styling automatically once built with Xcode 26, but custom-drawn tab bars (common in fintech) would not. Adoption claims beyond Fidelity are unverified.

### Gaps
- Robinhood's iOS release notes are uninformative ("Bug fixes and improvements"), so Liquid Glass adoption cannot be determined without hands-on inspection.
- No tech-press (9to5Mac/The Verge/TechCrunch) coverage of brokerage-app Liquid Glass redesigns was found.
