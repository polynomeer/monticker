"use client";

import { useEffect, useState } from "react";
import { ChartPieSlice, Info } from "@phosphor-icons/react";
import { Card } from "@/components/ui/Card";
import { Skeleton } from "@/components/ui/Skeleton";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";

interface StockScoreAxis {
  axis: string;
  label: string;
  available: boolean;
  score: number | null;
  detail: string | null;
}
interface StockScoreResponse {
  stockId: number;
  axes: StockScoreAxis[];
  isValuationPopulationMocked: boolean;
}

interface Props { stockId: number; }

const SCORE_LABEL: Record<number, string> = { 0: "고평가", 1: "적정", 2: "저평가" };

export default function StockScoreCard({ stockId }: Props) {
  const [data, setData] = useState<StockScoreResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const chartTheme = useThemeStore(s => CHART_THEMES[s.chartTheme]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetch(`/api/stocks/${stockId}/score`)
      .then(res => (res.ok ? res.json() : null))
      .then((json: StockScoreResponse | null) => { if (!cancelled) setData(json); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [stockId]);

  const valuation = data?.axes.find(a => a.axis === "VALUATION");
  const pending = data?.axes.filter(a => a.axis !== "VALUATION") ?? [];
  const scoreColor = valuation?.score === 2 ? chartTheme.upColor
    : valuation?.score === 0 ? chartTheme.downColor
    : undefined;

  return (
    <Card className="p-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="font-semibold text-gray-900 dark:text-dracula-fg flex items-center gap-1.5">
          <ChartPieSlice size={16} weight="bold" aria-hidden /> 종목 스코어
        </h3>
        {data?.isValuationPopulationMocked && (
          <span className="flex items-center gap-1 text-[10px] text-dracula-orange" title="KIS API 미설정 또는 응답 없음 — 비교 종목 상당수가 모의 데이터라 스코어 신뢰도가 낮음">
            <Info size={12} weight="bold" aria-hidden /> 모의 데이터
          </span>
        )}
      </div>

      {loading && (
        <div className="space-y-2">
          {[1, 2].map(i => <Skeleton key={i} className="h-8 w-full rounded" />)}
        </div>
      )}

      {!loading && valuation && (
        <>
          {valuation.available ? (
            <div className="flex items-center justify-between mb-4 px-3 py-2.5 rounded-lg bg-gray-50 dark:bg-dracula-line/10">
              <div>
                <p className="text-sm font-medium text-gray-900 dark:text-dracula-fg">{valuation.label}</p>
                <p className="text-[11px] text-gray-500 dark:text-dracula-comment mt-0.5">{valuation.detail}</p>
              </div>
              <span
                className="text-sm font-semibold tabular-nums px-2.5 py-1 rounded-full"
                style={{ color: scoreColor, backgroundColor: scoreColor ? `${scoreColor}1a` : undefined }}
              >
                {valuation.score !== null ? SCORE_LABEL[valuation.score] : "-"}
              </span>
            </div>
          ) : (
            <p className="text-xs text-gray-400 dark:text-dracula-comment mb-4">국내(KOSPI/KOSDAQ) 종목만 밸류에이션 스코어를 제공합니다.</p>
          )}

          <div className="flex flex-wrap gap-x-1.5 gap-y-1 text-[11px] text-gray-400 dark:text-dracula-comment">
            {pending.map(a => (
              <span key={a.axis} className="px-2 py-1 rounded-md bg-gray-50 dark:bg-dracula-line/10">
                {a.label} 준비중
              </span>
            ))}
          </div>
        </>
      )}
    </Card>
  );
}
