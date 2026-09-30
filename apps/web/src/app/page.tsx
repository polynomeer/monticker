"use client";

import { useState } from "react";
import ScreenerTable from "@/components/screener/ScreenerTable";
import WatchlistTicker from "@/components/home/WatchlistTicker";
import RecentlyViewedStocks from "@/components/home/RecentlyViewedStocks";
import PortfolioSnapshot from "@/components/home/PortfolioSnapshot";
import WatchlistSummary from "@/components/home/WatchlistSummary";
import MarketSummary from "@/components/home/MarketSummary";
import TopMovers from "@/components/home/TopMovers";
import RecentEvents from "@/components/home/RecentEvents";
import { Card } from "@/components/ui/Card";
import { useScreener } from "@/hooks/useScreener";

const TABS = [
  { key: "realtime", label: "실시간 차트" },
  { key: "movers",   label: "급등·급락" },
  { key: "foreign",  label: "외국인·기관 동향" },
];
const MARKETS = [
  { key: "all",      label: "전체" },
  { key: "domestic", label: "국내" },
  { key: "overseas", label: "해외" },
];
const SORTS = [
  { key: "amount", label: "거래대금순" },
  { key: "volume", label: "거래량순" },
  { key: "rise",   label: "급상승" },
  { key: "fall",   label: "급하락" },
];
const MARKET_CAP_TIERS = [
  { key: "all",   label: "시총 전체" },
  { key: "large", label: "대형주" },
  { key: "mid",   label: "중형주" },
  { key: "small", label: "소형주" },
];
const COLUMN_SETS = [
  { key: "basic",     label: "기본" },
  { key: "valuation", label: "밸류에이션" },
] as const;
type ColumnSet = (typeof COLUMN_SETS)[number]["key"];

function Pill({
  active, onClick, children,
}: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={`px-3 py-1.5 rounded-full text-xs font-medium whitespace-nowrap
        transition-all duration-300 ease-spring hover:scale-[1.04] active:scale-[0.96]
        ${active
          ? "bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg shadow-sm dark:shadow-glow-purple"
          : "bg-gray-100 dark:bg-dracula-line/50 text-gray-500 dark:text-dracula-comment hover:bg-gray-200 dark:hover:bg-dracula-line hover:text-gray-900 dark:hover:text-dracula-fg"
        }`}
    >
      {children}
    </button>
  );
}

export default function Home() {
  const [tab,           setTab]           = useState("realtime");
  const [market,        setMarket]        = useState("all");
  const [sort,          setSort]          = useState("amount");
  const [marketCapTier, setMarketCapTier] = useState("all");
  const [columnSet,     setColumnSet]     = useState<ColumnSet>("basic");

  const { items, total, hasMore, loading, loadingMore, loadMore, wsConnected } =
    useScreener(tab, market, sort, marketCapTier);

  const showMarketCapTier = market !== "overseas";

  return (
    // NavBar(h-14 = 56px) + layout pt-2 = 실질적으로 58px 오프셋
    <div className="flex flex-col">
      {/* 상단 실시간 틱커 — 전체 폭, 약간의 수평 패딩 */}
      <div className="px-3 sm:px-4">
        <WatchlistTicker />
      </div>

      {/* 데스크톱: 사이드바(280px) + 메인 2분할 | 모바일: 단일 컬럼 */}
      <div className="lg:grid lg:grid-cols-[280px_1fr] lg:divide-x dark:lg:divide-dracula-line/60">

        {/* ── 왼쪽 대시보드 사이드바 ─────────────────────────────────────── */}
        {/* sticky: NavBar(56px) + WatchlistTicker(약 36px) ≈ top-24 */}
        <aside className="lg:sticky lg:top-14 lg:h-[calc(100vh-3.5rem)] lg:overflow-y-auto
                          px-3 py-3 space-y-3
                          bg-gray-50/60 dark:bg-[#1e202a]
                          [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">

          {/* 모바일에서만 보이는 영역 — 데스크톱에서는 사이드바 안에 통합 */}
          <div className="lg:hidden">
            <RecentlyViewedStocks />
          </div>

          <PortfolioSnapshot />
          <WatchlistSummary />

          {/* 구분선 */}
          <div className="hidden lg:block border-t dark:border-dracula-line/60" />

          <MarketSummary />
          <TopMovers />

          {/* 구분선 */}
          <div className="hidden lg:block border-t dark:border-dracula-line/60" />

          <RecentEvents />
        </aside>

        {/* ── 오른쪽 스크리너 ──────────────────────────────────────────────── */}
        <main className="px-3 sm:px-4 py-3 min-w-0">

          {/* 데스크톱에서만 최근 본 종목 표시 (사이드바 밖) */}
          <div className="hidden lg:block mb-2">
            <RecentlyViewedStocks />
          </div>

          {/* 헤더 */}
          <div className="flex items-center justify-between mb-3 sm:mb-4">
            <div>
              <h1 className="text-lg sm:text-xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg">스크리너</h1>
              <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5 flex items-center gap-1.5">
                랭킹 10초 갱신
                <span className={`inline-flex items-center gap-1 font-medium ${wsConnected ? "text-market-up" : "text-gray-400 dark:text-dracula-comment"}`}>
                  <span className={`w-1.5 h-1.5 rounded-full ${wsConnected ? "bg-market-up animate-pulse" : "bg-gray-400 dark:bg-dracula-comment"}`} />
                  {wsConnected ? "실시간" : "연결 중..."}
                </span>
              </p>
            </div>
            <span className="text-xs text-gray-500 dark:text-dracula-comment tabular-nums">
              총 {total.toLocaleString()}개 종목
            </span>
          </div>

          {/* 탭 */}
          <div className="flex gap-1 mb-3 border-b border-gray-200 dark:border-dracula-line">
            {TABS.map(t => (
              <button
                key={t.key}
                onClick={() => { setTab(t.key); setSort("amount"); }}
                className={`px-3 py-2 text-sm font-medium transition-colors duration-300 ease-spring border-b-2 -mb-px
                  ${tab === t.key
                    ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple"
                    : "border-transparent text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"
                  }`}
              >
                {t.label}
              </button>
            ))}
          </div>

          {/* 필터 */}
          <div className="overflow-x-auto no-scrollbar -mx-3 sm:mx-0 px-3 sm:px-0 mb-2">
            <div className="flex gap-2 min-w-max">
              <div className="flex gap-1">
                {MARKETS.map(m => (
                  <Pill
                    key={m.key}
                    active={market === m.key}
                    onClick={() => {
                      setMarket(m.key);
                      if (m.key === "overseas") setMarketCapTier("all");
                    }}
                  >
                    {m.label}
                  </Pill>
                ))}
              </div>
              <div className="w-px bg-gray-200 dark:bg-dracula-line self-stretch mx-1" />
              <div className="flex gap-1">
                {SORTS.map(s => (
                  <Pill key={s.key} active={sort === s.key} onClick={() => setSort(s.key)}>
                    {s.label}
                  </Pill>
                ))}
              </div>
              {showMarketCapTier && (
                <>
                  <div className="w-px bg-gray-200 dark:bg-dracula-line self-stretch mx-1" />
                  <div className="flex gap-1">
                    {MARKET_CAP_TIERS.map(t => (
                      <Pill key={t.key} active={marketCapTier === t.key} onClick={() => setMarketCapTier(t.key)}>
                        {t.label}
                      </Pill>
                    ))}
                  </div>
                </>
              )}
            </div>
          </div>

          {/* 표시 컬럼 */}
          <div className="flex justify-end mb-3">
            <div className="inline-flex gap-1 p-0.5 rounded-lg bg-gray-100 dark:bg-dracula-line/30">
              {COLUMN_SETS.map(c => (
                <button
                  key={c.key}
                  onClick={() => setColumnSet(c.key)}
                  className={`px-2.5 py-1 rounded-md text-[11px] font-medium transition-all duration-200
                    ${columnSet === c.key
                      ? "bg-white dark:bg-dracula-bg text-gray-900 dark:text-dracula-fg shadow-sm"
                      : "text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"
                    }`}
                >
                  {c.label}
                </button>
              ))}
            </div>
          </div>

          {/* 테이블 */}
          <Card className="overflow-hidden">
            <ScreenerTable
              items={items}
              loading={loading}
              loadingMore={loadingMore}
              hasMore={hasMore}
              onLoadMore={loadMore}
              columnSet={columnSet}
            />
          </Card>
        </main>
      </div>
    </div>
  );
}
