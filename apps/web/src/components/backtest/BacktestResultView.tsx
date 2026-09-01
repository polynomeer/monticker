"use client";
import { useEffect, useRef, useState } from "react";
import { Card } from "@/components/ui/Card";

interface BacktestMetrics {
  totalReturn: number;
  sharpeRatio: number;
  maxDrawdown: number;
  winRate: number;
  profitTrades: number;
  totalTrades: number;
  profitFactor: number;
  avgHoldingDays: number;
}

interface BacktestTrade {
  entryDate: string;
  exitDate: string;
  entryPrice: number;
  exitPrice: number;
  pnlPct: number;
  exitReason: string;
}

interface EquityPoint {
  date: string;
  equity: number;
}

interface BacktestResult {
  strategy: string;
  symbol: string;
  fromDate: string;
  toDate: string;
  initialCapital: number;
  finalCapital: number;
  metrics: BacktestMetrics;
  trades: BacktestTrade[];
  equityCurve: EquityPoint[];
}

interface Props { result: BacktestResult; }

function MetricCard({ label, value, sub, color }: { label: string; value: string; sub?: string; color?: string }) {
  return (
    <div className="bg-gray-50 dark:bg-dracula-line/20 rounded-lg p-3">
      <p className="text-xs text-gray-500 dark:text-dracula-comment">{label}</p>
      <p className={`text-xl font-bold tabular-nums ${color ?? "text-gray-900 dark:text-dracula-fg"}`}>{value}</p>
      {sub && <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">{sub}</p>}
    </div>
  );
}

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }
function pct(n: number) { return `${n >= 0 ? "+" : ""}${n.toFixed(2)}%`; }
function color(n: number) { return n > 0 ? "text-[#ff5050]" : n < 0 ? "text-[#4a8fd4]" : "text-gray-500 dark:text-dracula-comment"; }

export default function BacktestResultView({ result }: Props) {
  const { metrics, trades, equityCurve, initialCapital, finalCapital, symbol } = result;
  const chartRef = useRef<HTMLDivElement>(null);
  const [resolvedTheme, setResolvedTheme] = useState<string>("light");

  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    setResolvedTheme(mq.matches ? "dark" : "light");
    const handler = (e: MediaQueryListEvent) => setResolvedTheme(e.matches ? "dark" : "light");
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, []);

  // Equity curve 차트
  useEffect(() => {
    if (!chartRef.current || !equityCurve?.length) return;
    let disposed = false;
    import("echarts").then(echarts => {
      if (disposed || !chartRef.current) return;
      const existing = echarts.getInstanceByDom(chartRef.current);
      if (existing) existing.dispose();

      const isDark = resolvedTheme === "dark";
      const chart = echarts.init(chartRef.current, undefined, { renderer: "canvas", height: 200 });

      chart.setOption({
        backgroundColor: isDark ? "#21222c" : "#fff",
        animation: false,
        tooltip: { trigger: "axis", backgroundColor: isDark ? "#282a36" : "#fff",
          borderColor: "#44475a", textStyle: { color: isDark ? "#f8f8f2" : "#374151", fontSize: 11 } },
        grid: { left: 60, right: 16, top: 16, bottom: 28 },
        xAxis: { type: "category", data: equityCurve.map(p => p.date),
          axisLabel: { color: isDark ? "#6272a4" : "#6b7280", fontSize: 10 },
          axisLine: { lineStyle: { color: isDark ? "#44475a" : "#e5e7eb" } } },
        yAxis: { type: "value", position: "left",
          axisLabel: { color: isDark ? "#6272a4" : "#6b7280", fontSize: 10,
            formatter: (v: number) => `${(v / 10000).toFixed(0)}만` },
          splitLine: { lineStyle: { color: isDark ? "#44475a" : "#e5e7eb", type: "dashed" } } },
        series: [
          { type: "line", data: equityCurve.map(p => p.equity),
            smooth: true, symbol: "none",
            lineStyle: { color: finalCapital >= initialCapital ? "#0ecb81" : "#f6465d", width: 2 },
            areaStyle: { color: { type: "linear", x: 0, y: 0, x2: 0, y2: 1,
              colorStops: [
                { offset: 0, color: finalCapital >= initialCapital ? "#0ecb8133" : "#f6465d33" },
                { offset: 1, color: "transparent" }] } } },
        ],
      });
      const onResize = () => chart.resize();
      window.addEventListener("resize", onResize);
      return () => { window.removeEventListener("resize", onResize); disposed = true; chart.dispose(); };
    });
    return () => { disposed = true; };
  }, [equityCurve, resolvedTheme, finalCapital, initialCapital]);

  return (
    <div className="space-y-4 animate-fade-up">
      {/* 요약 헤더 */}
      <Card className="p-4">
        <div className="flex items-center justify-between mb-3">
          <div>
            <span className="text-sm text-gray-500 dark:text-dracula-comment">{symbol} — {result.strategy}</span>
            <p className="text-xs text-gray-400 dark:text-dracula-line">{result.fromDate} ~ {result.toDate}</p>
          </div>
          <div className="text-right">
            <p className={`text-2xl font-bold tabular-nums ${color(metrics.totalReturn)}`}>{pct(metrics.totalReturn)}</p>
            <p className="text-xs text-gray-500 dark:text-dracula-comment">총 수익률</p>
          </div>
        </div>

        {/* 지표 그리드 */}
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-2">
          <MetricCard label="최종 자산" value={`₩${fmt(finalCapital)}`}
            sub={`초기 ₩${fmt(initialCapital)}`} color={color(finalCapital - initialCapital)} />
          <MetricCard label="Sharpe Ratio" value={metrics.sharpeRatio.toFixed(2)}
            color={metrics.sharpeRatio >= 1 ? "text-market-up" : metrics.sharpeRatio >= 0 ? "text-gray-900 dark:text-dracula-fg" : "text-market-down"} />
          <MetricCard label="최대 낙폭" value={`-${metrics.maxDrawdown.toFixed(1)}%`} color="text-market-down" />
          <MetricCard label="승률" value={`${metrics.winRate.toFixed(1)}%`}
            sub={`${metrics.profitTrades}/${metrics.totalTrades}건`} />
          <MetricCard label="Profit Factor" value={metrics.profitFactor.toFixed(2)}
            color={metrics.profitFactor >= 1 ? "text-market-up" : "text-market-down"} />
        </div>
      </Card>

      {/* 자산 곡선 */}
      <Card className="overflow-hidden">
        <div className="px-4 pt-3 text-xs text-gray-500 dark:text-dracula-comment font-medium">자산 곡선</div>
        <div ref={chartRef} className="w-full" />
      </Card>

      {/* 거래 내역 */}
      {trades.length > 0 && (
        <Card className="overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-200 dark:border-dracula-line bg-gray-50 dark:bg-transparent">
            <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">거래 내역</span>
            <span className="ml-2 text-xs text-gray-500 dark:text-dracula-comment">평균 보유 {metrics.avgHoldingDays.toFixed(1)}일</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead><tr className="border-b border-gray-200 dark:border-dracula-line text-gray-500 dark:text-dracula-comment">
                {["매수일","매도일","매수가","매도가","수익률","사유"].map(h =>
                  <th key={h} className="px-3 py-2 text-left">{h}</th>)}
              </tr></thead>
              <tbody>
                {trades.map((t, i) => (
                  <tr key={i} className="border-b border-gray-100 dark:border-dracula-line/40 hover:bg-gray-50 dark:hover:bg-dracula-line/10 transition-colors">
                    <td className="px-3 py-2 text-gray-500 dark:text-dracula-comment tabular-nums">{t.entryDate}</td>
                    <td className="px-3 py-2 text-gray-500 dark:text-dracula-comment tabular-nums">{t.exitDate}</td>
                    <td className="px-3 py-2 font-mono tabular-nums text-gray-900 dark:text-dracula-fg">{fmt(t.entryPrice)}</td>
                    <td className="px-3 py-2 font-mono tabular-nums text-gray-900 dark:text-dracula-fg">{fmt(t.exitPrice)}</td>
                    <td className={`px-3 py-2 font-mono tabular-nums font-bold ${color(t.pnlPct)}`}>{pct(t.pnlPct)}</td>
                    <td className="px-3 py-2">
                      <span className={`px-1.5 py-0.5 rounded text-[10px] ${
                        t.exitReason === "TAKE_PROFIT" ? "bg-market-up/20 text-market-up" :
                        t.exitReason === "STOP_LOSS"   ? "bg-market-down/20 text-market-down" :
                        "bg-gray-100 text-gray-500 dark:bg-dracula-line dark:text-dracula-comment"
                      }`}>{t.exitReason === "TAKE_PROFIT" ? "익절" : t.exitReason === "STOP_LOSS" ? "손절" : t.exitReason === "SIGNAL" ? "신호" : "종료"}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  );
}
