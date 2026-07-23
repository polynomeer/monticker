"use client";

import { useState } from "react";
import ScreenerTable from "@/components/screener/ScreenerTable";
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
      className={`px-3 py-1.5 rounded-full text-xs font-medium transition-colors whitespace-nowrap
        ${active
          ? "bg-[#bd93f9] text-[#282a36]"
          : "bg-[#44475a] text-[#6272a4] hover:text-[#f8f8f2]"
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
    <div className="max-w-6xl mx-auto px-3 sm:px-4 py-4 sm:py-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-4 sm:mb-6">
        <div>
          <h1 className="text-xl font-bold dark:text-[#f8f8f2]">스크리너</h1>
          <p className="text-xs text-[#6272a4] mt-0.5 flex items-center gap-1.5">
            랭킹 10초 갱신
            <span className={`font-medium ${wsConnected ? "text-[#50fa7b]" : "text-[#6272a4]"}`}>
              ● {wsConnected ? "실시간" : "연결 중..."}
            </span>
          </p>
        </div>
        <span className="text-xs text-[#6272a4]">총 {total.toLocaleString()}개 종목</span>
      </div>

      {/* 탭 */}
      <div className="flex gap-1 mb-4 border-b border-[#44475a]">
        {TABS.map(t => (
          <button
            key={t.key}
            onClick={() => { setTab(t.key); setSort("amount"); }}
            className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
              ${tab === t.key
                ? "border-[#bd93f9] text-[#bd93f9]"
                : "border-transparent text-[#6272a4] hover:text-[#f8f8f2]"
              }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* 필터 — 모바일에서 가로 스크롤 */}
      <div className="overflow-x-auto -mx-3 sm:mx-0 px-3 sm:px-0 mb-4">
        <div className="flex gap-2 min-w-max">
          <div className="flex gap-1">
            {MARKETS.map(m => (
              <Pill key={m.key} active={market === m.key} onClick={() => setMarket(m.key)}>
                {m.label}
              </Pill>
            ))}
          </div>
          <div className="w-px bg-[#44475a] self-stretch mx-1" />
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
      <div className="border border-[#44475a] rounded-xl overflow-hidden dark:bg-[#282a36]">
        <ScreenerTable
          items={items}
          loading={loading}
          loadingMore={loadingMore}
          hasMore={hasMore}
          onLoadMore={loadMore}
        />
      </div>
    </div>
  );
}
