"use client";

import { useState } from "react";
import ScreenerTable from "@/components/screener/ScreenerTable";
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
  const [tab,    setTab]    = useState("realtime");
  const [market, setMarket] = useState("all");
  const [sort,   setSort]   = useState("amount");

  const { items, total, hasMore, loading, loadingMore, loadMore, wsConnected } =
    useScreener(tab, market, sort);

  return (
    <div className="max-w-6xl mx-auto px-3 sm:px-4 py-4 sm:py-8 animate-fade-up">
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-4 sm:mb-6">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold tracking-tight text-gray-900 dark:text-[#f8f8f2]">스크리너</h1>
          <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-1 flex items-center gap-1.5">
            랭킹 10초 갱신
            <span className={`inline-flex items-center gap-1 font-medium ${wsConnected ? "text-[#0ecb81]" : "text-gray-400 dark:text-[#6272a4]"}`}>
              <span className={`w-1.5 h-1.5 rounded-full ${wsConnected ? "bg-[#0ecb81] animate-pulse" : "bg-gray-400 dark:bg-[#6272a4]"}`} />
              {wsConnected ? "실시간" : "연결 중..."}
            </span>
          </p>
        </div>
        <span className="text-xs text-gray-500 dark:text-[#6272a4] tabular-nums">총 {total.toLocaleString()}개 종목</span>
      </div>

      {/* 탭 */}
      <div className="flex gap-1 mb-4 border-b border-gray-200 dark:border-[#44475a]">
        {TABS.map(t => (
          <button
            key={t.key}
            onClick={() => { setTab(t.key); setSort("amount"); }}
            className={`px-4 py-2 text-sm font-medium transition-colors duration-300 ease-spring border-b-2 -mb-px
              ${tab === t.key
                ? "border-blue-600 dark:border-[#bd93f9] text-blue-600 dark:text-[#bd93f9]"
                : "border-transparent text-gray-500 dark:text-[#6272a4] hover:text-gray-900 dark:hover:text-[#f8f8f2]"
              }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* 필터 — 모바일에서 가로 스크롤 */}
      <div className="overflow-x-auto no-scrollbar -mx-3 sm:mx-0 px-3 sm:px-0 mb-4">
        <div className="flex gap-2 min-w-max">
          <div className="flex gap-1">
            {MARKETS.map(m => (
              <Pill key={m.key} active={market === m.key} onClick={() => setMarket(m.key)}>
                {m.label}
              </Pill>
            ))}
          </div>
          <div className="w-px bg-gray-200 dark:bg-[#44475a] self-stretch mx-1" />
          <div className="flex gap-1">
            {SORTS.map(s => (
              <Pill key={s.key} active={sort === s.key} onClick={() => setSort(s.key)}>
                {s.label}
              </Pill>
            ))}
          </div>
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
        />
      </Card>
    </div>
  );
}
