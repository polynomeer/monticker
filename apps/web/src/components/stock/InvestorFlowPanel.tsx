"use client";

import { useEffect, useState } from "react";
import { Users, Info } from "@phosphor-icons/react";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { Skeleton } from "@/components/ui/Skeleton";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";

interface InvestorFlowDay {
  tradeDate: string;
  individualNetAmount: number;
  foreignNetAmount: number;
  institutionNetAmount: number;
  isMocked: boolean;
}
interface InvestorFlowResult {
  stockId: number;
  days: InvestorFlowDay[];
  isAnyMocked: boolean;
}

interface Props { stockId: number; }

function fmtAmount(n: number) {
  const sign = n < 0 ? "-" : "+";
  const abs = Math.abs(n);
  const body =
    abs >= 100_000_000 ? `${(abs / 100_000_000).toFixed(1)}억` :
    abs >= 10_000       ? `${(abs / 10_000).toFixed(0)}만` :
    abs.toLocaleString("ko-KR");
  return `${sign}${body}`;
}

export default function InvestorFlowPanel({ stockId }: Props) {
  const [data, setData] = useState<InvestorFlowResult | null>(null);
  const [loading, setLoading] = useState(true);
  const chartTheme = useThemeStore(s => CHART_THEMES[s.chartTheme]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetch(`/api/stocks/${stockId}/investor-flow?days=10`)
      .then(res => (res.ok ? res.json() : null))
      .then((json: InvestorFlowResult | null) => { if (!cancelled) setData(json); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [stockId]);

  return (
    <Card className="p-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="font-semibold text-gray-900 dark:text-dracula-fg flex items-center gap-1.5">
          <Users size={16} weight="bold" aria-hidden /> 개인·외국인·기관 순매수
        </h3>
        {data?.isAnyMocked && (
          <span className="flex items-center gap-1 text-[10px] text-dracula-orange" title="KIS API 미설정 또는 응답 없음 — 모의 데이터로 대체됨">
            <Info size={12} weight="bold" aria-hidden /> 모의 데이터
          </span>
        )}
      </div>

      {loading && (
        <div className="space-y-2">
          {[1, 2, 3].map(i => <Skeleton key={i} className="h-6 w-full rounded" />)}
        </div>
      )}

      {!loading && (!data || data.days.length === 0) && (
        <EmptyState
          icon={Users}
          title="수급 데이터 없음"
          description="국내(KOSPI/KOSDAQ) 종목만 제공됩니다."
          className="py-8"
        />
      )}

      {!loading && data && data.days.length > 0 && (() => {
        const latest = data.days[0];
        const rows = [
          { label: "개인", value: latest.individualNetAmount },
          { label: "외국인", value: latest.foreignNetAmount },
          { label: "기관", value: latest.institutionNetAmount },
        ];
        const max = Math.max(1, ...rows.map(r => Math.abs(r.value)));

        return (
          <>
            {/* 최근일 순매수 바 */}
            <div className="space-y-2 mb-4">
              {rows.map(r => {
                const up = r.value >= 0;
                const color = up ? chartTheme.upColor : chartTheme.downColor;
                const pct = (Math.abs(r.value) / max) * 100;
                return (
                  <div key={r.label} className="flex items-center gap-2">
                    <span className="w-10 text-xs text-gray-500 dark:text-dracula-comment shrink-0">{r.label}</span>
                    <div className="flex-1 h-2 rounded-full bg-gray-100 dark:bg-dracula-line overflow-hidden">
                      <div className="h-full rounded-full transition-all duration-300" style={{ width: `${pct}%`, backgroundColor: color }} />
                    </div>
                    <span className="w-16 text-right text-xs font-mono font-semibold tabular-nums" style={{ color }}>
                      {fmtAmount(r.value)}
                    </span>
                  </div>
                );
              })}
            </div>

            {/* 일별 테이블 */}
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead>
                  <tr className="text-gray-400 dark:text-dracula-comment border-b border-gray-100 dark:border-dracula-line">
                    <th className="text-left font-medium py-1.5">날짜</th>
                    <th className="text-right font-medium py-1.5">개인</th>
                    <th className="text-right font-medium py-1.5">외국인</th>
                    <th className="text-right font-medium py-1.5">기관</th>
                  </tr>
                </thead>
                <tbody>
                  {data.days.map(d => (
                    <tr key={d.tradeDate} className="border-b border-gray-50 dark:border-dracula-line/40 last:border-0">
                      <td className="py-1.5 text-gray-500 dark:text-dracula-comment tabular-nums">
                        {new Date(d.tradeDate).toLocaleDateString("ko-KR", { month: "2-digit", day: "2-digit" })}
                      </td>
                      <td className="py-1.5 text-right font-mono tabular-nums" style={{ color: d.individualNetAmount >= 0 ? chartTheme.upColor : chartTheme.downColor }}>
                        {fmtAmount(d.individualNetAmount)}
                      </td>
                      <td className="py-1.5 text-right font-mono tabular-nums" style={{ color: d.foreignNetAmount >= 0 ? chartTheme.upColor : chartTheme.downColor }}>
                        {fmtAmount(d.foreignNetAmount)}
                      </td>
                      <td className="py-1.5 text-right font-mono tabular-nums" style={{ color: d.institutionNetAmount >= 0 ? chartTheme.upColor : chartTheme.downColor }}>
                        {fmtAmount(d.institutionNetAmount)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        );
      })()}
    </Card>
  );
}
