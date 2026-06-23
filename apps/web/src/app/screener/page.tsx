"use client";

import { useState } from "react";
import ScreenerTable from "@/components/screener/ScreenerTable";
import { useScreener } from "@/hooks/useScreener";

const TABS    = [
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
  { key: "amount",  label: "거래대금순" },
  { key: "volume",  label: "거래량순" },
  { key: "rise",    label: "급상승" },
  { key: "fall",    label: "급하락" },
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

export default function ScreenerPage() {
  const [tab,    setTab]    = useState("realtime");
  const [market, setMarket] = useState("all");
  const [sort,   setSort]   = useState("amount");

  const { items, total, hasMore, loading, loadingMore, loadMore, wsConnected } =
    useScreener(tab, market, sort);

  return (
    <div className="max-w-6xl mx-auto p-6">
      {/* 헤더 */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold dark:text-[#f8f8f2]">종목 스크리너</h1>
        <p className="text-sm text-[#6272a4] mt-1 flex items-center gap-2">랭킹 10초 갱신 · <span className={wsConnected ? "text-[#0ecb81]" : "text-[#6272a4]"}>● {wsConnected ? "실시간 연결됨" : "연결 중..."}</span></p>
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

      {/* 필터 */}
      <div className="flex flex-wrap gap-2 mb-4">
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
        <div className="ml-auto text-xs text-[#6272a4] self-center">
          총 {total.toLocaleString()}개 종목
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
