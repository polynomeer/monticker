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
    <div className="border border-blue-100 dark:border-[#44475a] bg-blue-50 dark:bg-[#282a36] rounded-lg p-4">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-blue-600 dark:text-[#bd93f9] font-semibold text-sm">AI 요약</span>
        <span className="text-xs text-blue-400 dark:text-[#6272a4]">({symbol})</span>
        {isLoading && <span className="text-xs text-blue-400 dark:text-[#6272a4] animate-pulse">분석 중...</span>}
      </div>
      {summary ? (
        <p className="text-sm text-gray-700 dark:text-[#f8f8f2] leading-relaxed">{summary}</p>
      ) : isLoading ? (
        <div className="h-12 bg-blue-100 dark:bg-[#44475a] rounded animate-pulse" />
      ) : (
        <p className="text-sm text-gray-400 dark:text-[#6272a4]">요약을 불러올 수 없습니다.</p>
      )}
    </div>
  );
}
