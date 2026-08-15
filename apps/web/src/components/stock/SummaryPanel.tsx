"use client";

import { useQuery } from "@tanstack/react-query";
import { stockKeys } from "@/hooks/useStockChart";

interface Props { stockId: number; symbol: string; }

export default function SummaryPanel({ stockId, symbol }: Props) {
  const { data, isLoading } = useQuery<{ summary: string }>({
    queryKey:        stockKeys.summary(stockId),
    queryFn:         async () => {
      const res = await fetch(`/api/stocks/${stockId}/summary`);
      return res.ok ? res.json() : { summary: null };
    },
    refetchInterval: 60_000,
    staleTime:       60_000,
  });

  const summary = data?.summary ?? null;

  return (
    <div className="border border-blue-100 dark:border-dracula-line bg-blue-50 dark:bg-dracula-bg rounded-lg p-4">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-blue-600 dark:text-dracula-purple font-semibold text-sm">AI 요약</span>
        <span className="text-xs text-blue-400 dark:text-dracula-comment">({symbol})</span>
        {isLoading && <span className="text-xs text-blue-400 dark:text-dracula-comment animate-pulse">분석 중...</span>}
      </div>
      {summary ? (
        <p className="text-sm text-gray-700 dark:text-dracula-fg leading-relaxed">{summary}</p>
      ) : isLoading ? (
        <div className="h-12 bg-blue-100 dark:bg-dracula-line rounded animate-pulse" />
      ) : (
        <p className="text-sm text-gray-400 dark:text-dracula-comment">요약을 불러올 수 없습니다.</p>
      )}
    </div>
  );
}
