"use client";

import { useMarketSummaryWs } from "@/hooks/useMarketSummaryWs";

const MAX_DISPLAY = 12;

function rateClass(rate: number | null) {
  if (rate == null || rate === 0) return "text-gray-500 dark:text-dracula-comment";
  return rate > 0 ? "text-market-up" : "text-market-down";
}

export default function MarketSummary() {
  const { summary, connected } = useMarketSummaryWs();
  // ADR-039: 서버가 정한 거래대금 상위 — 이전엔 전역 스트림에서 "우연히 먼저 도착한 12개"라 비결정적이었다.
  const list = summary?.topByAmount.slice(0, MAX_DISPLAY) ?? [];

  return (
    <div className="border border-gray-200 dark:border-dracula-line dark:bg-dracula-bg rounded-lg p-4">
      <div className="flex items-center justify-between mb-3">
        <h2 className="font-semibold dark:text-dracula-fg">실시간 시세</h2>
        <div className="flex items-center gap-2">
          {summary && (
            <span className="text-xs text-gray-500 dark:text-dracula-comment">
              <span className="text-market-up">▲{summary.advancers}</span>
              {" · "}
              <span className="text-market-down">▼{summary.decliners}</span>
            </span>
          )}
          <span className={`text-xs px-2 py-0.5 rounded-full ${connected ? "bg-green-50 dark:bg-market-up/15 text-green-600 dark:text-market-up" : "bg-gray-50 dark:bg-dracula-line text-gray-400 dark:text-dracula-comment"}`}>
            {connected ? "● 연결됨" : "○ 연결 중"}
          </span>
        </div>
      </div>
      {list.length === 0 ? (
        <p className="text-gray-400 dark:text-dracula-comment text-sm py-2 text-center">Worker 실행 후 시세가 표시됩니다.</p>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          {list.map(p => (
            <div key={p.stockId} className="border border-gray-100 dark:border-dracula-line dark:bg-dracula-line rounded p-2">
              <p className="text-xs text-gray-500 dark:text-dracula-comment truncate">{p.symbol} · {p.name}</p>
              <p className="font-bold text-sm dark:text-dracula-fg">{p.price.toLocaleString()}</p>
              <p className={`text-xs ${rateClass(p.changeRate)}`}>
                {p.changeRate == null ? "—" : `${p.changeRate > 0 ? "+" : ""}${p.changeRate.toFixed(2)}%`}
              </p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
