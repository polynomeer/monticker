"use client";

import Link from "next/link";
import { useRecentlyViewedStocks } from "@/hooks/useRecentlyViewedStocks";

function isDomestic(market: string) { return market === "KOSPI" || market === "KOSDAQ"; }

export default function RecentlyViewedStocks() {
  const { entries, clear } = useRecentlyViewedStocks();

  if (entries.length === 0) return null;

  return (
    <div className="mb-4">
      <div className="flex items-center justify-between mb-2">
        <h2 className="text-xs font-semibold text-gray-500 dark:text-dracula-comment">최근 본 종목</h2>
        <button
          onClick={clear}
          className="text-[10px] text-gray-400 dark:text-dracula-comment hover:text-gray-600 dark:hover:text-dracula-fg transition-colors"
        >
          지우기
        </button>
      </div>
      <div className="flex gap-2 overflow-x-auto no-scrollbar pb-1">
        {entries.map(e => (
          <Link
            key={e.stockId}
            href={`/stocks/${e.symbol}`}
            className="shrink-0 flex flex-col gap-0.5 px-3 py-2 rounded-lg border border-gray-200 dark:border-dracula-line
                       bg-white dark:bg-dracula-bg hover:border-gray-300 dark:hover:border-dracula-comment
                       hover:bg-gray-50 dark:hover:bg-dracula-line/30 transition-colors min-w-[96px]"
          >
            <span className="text-xs font-medium text-gray-900 dark:text-dracula-fg truncate max-w-[112px]">{e.name}</span>
            <span className="text-[10px] text-gray-400 dark:text-dracula-comment">
              {e.symbol} · {isDomestic(e.market) ? "국내" : "해외"}
            </span>
          </Link>
        ))}
      </div>
    </div>
  );
}
