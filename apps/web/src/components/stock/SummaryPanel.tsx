"use client";

import { useEffect, useState } from "react";

interface Props {
  stockId: number;
  symbol: string;
}

export default function SummaryPanel({ stockId, symbol }: Props) {
  const [summary, setSummary] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchSummary = async () => {
      setLoading(true);
      try {
        const res = await fetch(`/api/stocks/${stockId}/summary`);
        if (res.ok) {
          const data = await res.json();
          setSummary(data.summary);
        }
      } finally {
        setLoading(false);
      }
    };
    fetchSummary();
    const id = setInterval(fetchSummary, 60_000);
    return () => clearInterval(id);
  }, [stockId]);

  return (
    <div className="border border-blue-100 bg-blue-50 rounded-lg p-4">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-blue-600 font-semibold text-sm">AI 요약</span>
        <span className="text-xs text-blue-400">({symbol})</span>
        {loading && <span className="text-xs text-blue-400 animate-pulse">분석 중...</span>}
      </div>
      {summary ? (
        <p className="text-sm text-gray-700 leading-relaxed">{summary}</p>
      ) : loading ? (
        <div className="h-12 bg-blue-100 rounded animate-pulse" />
      ) : (
        <p className="text-sm text-gray-400">요약을 불러올 수 없습니다.</p>
      )}
    </div>
  );
}
