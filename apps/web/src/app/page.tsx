"use client";

import { useState } from "react";
import ScreenerTable from "@/components/screener/ScreenerTable";
import WatchlistTicker from "@/components/home/WatchlistTicker";
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

  // 해외 종목은 펀더멘털 수집 대상이 아니라 시총 필터가 항상 빈 결과를 반환하므로 숨기고 초기화
  const showMarketCapTier = market !== "overseas";

  return (
    <div className="max-w-6xl mx-auto px-3 sm:px-4 py-4 sm:py-8 animate-fade-up">
      <WatchlistTicker />

      {/* 헤더 */}
      <div className="flex items-center justify-between mb-4 sm:mb-6">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg">스크리너</h1>
          <p className="text-xs text-gray-500 dark:text-dracula-comment mt-1 flex items-center gap-1.5">
            랭킹 10초 갱신
            <span className={`inline-flex items-center gap-1 font-medium ${wsConnected ? "text-market-up" : "text-gray-400 dark:text-dracula-comment"}`}>
              <span className={`w-1.5 h-1.5 rounded-full ${wsConnected ? "bg-market-up animate-pulse" : "bg-gray-400 dark:bg-dracula-comment"}`} />
              {wsConnected ? "실시간" : "연결 중..."}
            </span>
          </p>
        </div>
        <span className="text-xs text-gray-500 dark:text-dracula-comment tabular-nums">총 {total.toLocaleString()}개 종목</span>
      </div>

      {/* 탭 */}
      <div className="flex gap-1 mb-4 border-b border-gray-200 dark:border-dracula-line">
        {TABS.map(t => (
          <button
            key={t.key}
            onClick={() => { setTab(t.key); setSort("amount"); }}
            className={`px-4 py-2 text-sm font-medium transition-colors duration-300 ease-spring border-b-2 -mb-px
              ${tab === t.key
                ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple"
                : "border-transparent text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"
              }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* 필터 — 무엇을 거를지. 모바일에서 가로 스크롤 */}
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

      {/* 표시 컬럼 — 무엇을 볼지. 필터와 독립적으로 클라이언트에서만 전환 */}
      <div className="flex justify-end mb-4">
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
    </div>
  );
}
